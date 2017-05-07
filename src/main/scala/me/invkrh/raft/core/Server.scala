package me.invkrh.raft.core

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Scheduler}
import akka.pattern.{after, ask, pipe}
import akka.util.Timeout
import me.invkrh.raft.core.Exception._
import me.invkrh.raft.core.Message.{RequestVote, StartElection, Tick, _}
import me.invkrh.raft.util._

object Server {
  def props(id: Int,
            minElectionTime: FiniteDuration,
            maxElectionTime: FiniteDuration,
            tickTime: FiniteDuration): Props = {
    checkOrThrow(minElectionTime > tickTime, HeartbeatIntervalException())
    Props(new Server(id, minElectionTime, maxElectionTime, tickTime))
  }

  def props(conf: ServerConf): Props = {
    props(conf.id, conf.minElectionTime, conf.maxElectionTime, conf.tickTime)
  }

  def run(id: Int,
          minElectionTime: FiniteDuration,
          maxElectionTime: FiniteDuration,
          tickTime: FiniteDuration,
          name: String)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(id, minElectionTime, maxElectionTime, tickTime), name)
  }

  def run(serverConf: ServerConf)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(serverConf), "svr-" + serverConf.id)
  }
}

// TODO: use --bootstrap.servers as input arguments
class Server(val id: Int,
             minElectionTime: FiniteDuration,
             maxElectionTime: FiniteDuration,
             tickTime: FiniteDuration)
    extends Actor
    with Logging {

  def this(conf: ServerConf) =
    this(conf.id, conf.minElectionTime, conf.maxElectionTime, conf.tickTime)

  import context._
  implicit val scheduler: Scheduler = system.scheduler
  override def logPrefix: String = s"[$id:$curState]"

  var curTerm = 0
  var curState: State.Value = State.Init
  var curLeaderId: Option[Int] = None
  var votedFor: Option[Int] = None
  var members: Map[Int, ActorRef] = Map()

  val clientMessageCache = new MessageCache[ClientMessage]()

  val electionTimer =
    new RandomizedTimer(minElectionTime, maxElectionTime, StartElection)
  val heartBeatTimer = new PeriodicTimer(tickTime, Tick)
  val logs: ArrayBuffer[LogEntry] = new ArrayBuffer[LogEntry]()

  // TODO: Member Management problem, member is added one by one

  override def preStart(): Unit = {}

  override def postStop(): Unit = {
    info(s"Server $id stops and cancel all timer scheduled tasks")
    electionTimer.stop()
    heartBeatTimer.stop()
  }

  def retry[T](ft: Future[T],
               delay: FiniteDuration,
               retries: Int,
               retryMsg: String = ""): Future[T] = {
    ft recoverWith {
      case e if retries > 0 =>
        warn(retryMsg + " current error: " + e)
        after(delay, scheduler)(retry(ft, delay, retries - 1, retryMsg))
    }
  }

  // TODO: retries is a conf ?
  def distributeRPC(msg: RPCMessage, retries: Int = 1): Unit = {
    implicit val timeout = Timeout(tickTime / retries)
    Future
      .sequence {
        for {
          (id, follower) <- members.toSeq if id != this.id
        } yield {
          retry(
            (follower ? msg).mapTo[RPCResult],
            Duration.Zero,
            retries,
            s"Can not reach ${follower.path}, retrying ..."
          ).map(x => Success(x))
            .recover { case e => Failure(e) }
            .map(resp => (follower, resp))
        }
      }
      .map { res =>
        CallBack(msg, res)
      } pipeTo self
  }

  def processCallBack(callback: CallBack)(majorityHandler: Int => Unit): Unit = {
    val results = callback.responses
    val (maxTerm, validReplyCount) = results.foldLeft(curTerm, 1) {
      case ((curMaxTerm, count), (follower, tryRes)) =>
        tryRes match {
          case Success(res) =>
            if (res.term > curTerm) {
              info(s"New leader is detected by receiving $res from ${follower.path}")
              (Math.max(curMaxTerm, res.term), count)
            } else if (res.term == curTerm) {
              info(s"Receive $res from ${follower.path}")
              if (res.success) {
                (curMaxTerm, count + 1)
              } else {
                (curMaxTerm, count)
              }
            } else {
              throw new Exception(
                s"The term of reply received (${res.term}) must not be smaller than is current " +
                  s"term ($curTerm)"
              )
            }
          case Failure(e) =>
            warn(s"Can not get ${callback.request} from ${follower.path} with error: " + e)
            (curMaxTerm, count)

        }
    }
    if (maxTerm > curTerm) {
      becomeFollower(maxTerm)
    } else {
      if (validReplyCount > members.size / 2) {
        majorityHandler(validReplyCount)
      } else {
        info(
          s"Majority is not reached ($validReplyCount / ${members.size}), " +
            s"a new round will be launched"
        )
      }
    }
    info(s"=== end of processing ${callback.request} ===")
  }

  def issueVoteRequest(): Unit = {
    distributeRPC(RequestVote(curTerm, id, 0, 0))
  }

  def issueHeartbeat(): Unit = {
    distributeRPC(AppendEntries(curTerm, id, 0, 0, Seq(), 0))
  }

  def processClientRequest(cmd: Command): Unit = {
    info("Client command received: " + cmd)
  }

  // TODO: Add admin endpoint

  def startElectionEndpoint: Receive = {
    case StartElection => becomeCandidate()
  }

  def tickEndPoint: Receive = {
    case Tick => issueHeartbeat()
  }

  def appendEntriesEndPoint: Receive = {
    case hb: AppendEntries =>
      if (curTerm > hb.term) {
        sender ! AppendEntriesResult(curTerm, success = false)
      } else if (curTerm == hb.term) {
        curState match {
          case State.Leader => throw new Exception("Two leader detected")
          case State.Candidate =>
            // TODO: Add precessing
            sender ! AppendEntriesResult(hb.term, success = true)
            becomeFollower(hb.term, hb.leaderId)
          case State.Follower =>
            curLeaderId foreach { leaderId =>
              if (leaderId != hb.leaderId) {
                throw new Exception("Two leader detected")
              }
            }
            // TODO: Add precessing
            sender ! AppendEntriesResult(hb.term, success = true)
            electionTimer.restart()
        }
      } else {
        // TODO: Add precessing
        sender ! AppendEntriesResult(hb.term, success = true)
        becomeFollower(hb.term, hb.leaderId)
      }
  }

  def requestVoteEndPoint: Receive = {
    case reqVote: RequestVote =>
      if (curTerm > reqVote.term) {
        sender ! RequestVoteResult(curTerm, success = false)
      } else if (curTerm == reqVote.term) {
        sender ! RequestVoteResult(curTerm, success = false)
      } else {
        curState match {
          case State.Follower =>
            if (votedFor.isEmpty || votedFor.get == reqVote.candidateId) {
              votedFor = Some(reqVote.candidateId)
              sender ! RequestVoteResult(reqVote.term, success = true)
              electionTimer.restart()
            } else {
              sender ! RequestVoteResult(reqVote.term, success = false)
            }
          case State.Candidate | State.Leader =>
            sender ! RequestVoteResult(reqVote.term, success = true)
            becomeFollower(reqVote.term)
        }
      }
  }

  def commandEndPoint: Receive = {
    case cmd: Command =>
      curState match {
        case State.Leader =>
          processClientRequest(cmd)
          sender() ! CommandResponse(success = true)
        case State.Candidate =>
          curLeaderId match {
            case Some(_) => throw new Exception("Leader should be empty for candidate")
            case None => clientMessageCache.add(sender, cmd)
          }
        case State.Follower =>
          curLeaderId match {
            case Some(leaderId) =>
              val leaderRef =
                members.getOrElse(leaderId, throw new Exception("Unknown leader id: " + leaderId))
              leaderRef forward cmd
            case None => clientMessageCache.add(sender, cmd)
          }
      }
  }

  def callBackEndPoint(majorityHandler: Int => Unit): Receive = {
    case cb: CallBack => processCallBack(cb)(majorityHandler)
  }

  def irrelevantMsgEndPoint: Receive = {
    case irrelevantMsg: RPCMessage =>
      warn(s"Irrelevant message [$irrelevantMsg] received from ${sender().path}")
  }

  def follower: Receive =
    commandEndPoint orElse
      startElectionEndpoint orElse
      appendEntriesEndPoint orElse
      requestVoteEndPoint orElse
      irrelevantMsgEndPoint

  def candidate: Receive =
    callBackEndPoint { majCnt =>
      info(
        s"Election for term $curTerm is ended since majority is reached " +
          s"($majCnt / ${members.size})"
      )
      becomeLeader()
    } orElse
      commandEndPoint orElse
      startElectionEndpoint orElse
      appendEntriesEndPoint orElse
      requestVoteEndPoint orElse
      irrelevantMsgEndPoint

  def leader: Receive =
    callBackEndPoint { majCnt =>
      info(
        s"Heartbeat for term $curTerm is ended since majority is reached " +
          s"($majCnt / ${members.size}), committing logs"
      )
    } orElse
      commandEndPoint orElse
      tickEndPoint orElse
      appendEntriesEndPoint orElse
      requestVoteEndPoint orElse
      irrelevantMsgEndPoint

  override def receive: Receive = {
    case Init(memberDict) =>
      info(s"Server $id initialized")
      members = memberDict
      becomeFollower(curTerm)
  }

  def becomeFollower(newTerm: Int, newLeader: Int = -1): Unit = {
    curTerm = newTerm
    curState = State.Follower
    votedFor = None
//    info(s"Status: Follower, current term: $curTerm")
    if (newLeader == -1) { // Unknown new leader detected
      curLeaderId = None
    } else { // Known new leader detected
      curLeaderId = Some(newLeader)
      for {
        leaderId <- curLeaderId
        leaderRef <- members.get(leaderId)
      } yield {
        clientMessageCache.flushTo(leaderRef)
      }
    }
    become(follower)
    heartBeatTimer.stop()
    electionTimer.restart()
  }

  def becomeCandidate(): Unit = {
    curTerm = curTerm + 1
    curState = State.Candidate
    votedFor = Some(id)
    curLeaderId = None
    info(s"Election for term $curTerm started, server $id becomes candidate")
    become(candidate)
    issueVoteRequest()
    electionTimer.restart()
  }

  def becomeLeader(): Unit = {
    // term not changed
    curState = State.Leader
    curLeaderId = Some(id)
    votedFor = None
    info(s"Server $id becomes leader")
    become(leader)
    clientMessageCache.flushTo(self)
    electionTimer.stop()
    heartBeatTimer.restart()
  }

}
