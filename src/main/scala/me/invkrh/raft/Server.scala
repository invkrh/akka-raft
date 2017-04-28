package me.invkrh.raft

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Scheduler}
import akka.pattern.{after, ask, pipe}
import akka.util.Timeout
import me.invkrh.raft.RaftMessage._
import me.invkrh.raft.Exception._
import me.invkrh.raft.util._

object Server {
  def props(id: Int,
            minElectionTime: FiniteDuration,
            maxElectionTime: FiniteDuration,
            tickTime: FiniteDuration,
            memberDict: Map[Int, String]): Props = {
    checkOrThrow(minElectionTime > tickTime, HeartbeatIntervalException())
    checkOrThrow(memberDict.nonEmpty, EmptyInitMemberException())
    Props(new Server(id, minElectionTime, maxElectionTime, tickTime, memberDict))
  }

  def props(conf: ServerConf): Props = {
    props(conf.id, conf.minElectionTime, conf.maxElectionTime, conf.tickTime, conf.memberDict)
  }

  def run(id: Int,
          minElectionTime: FiniteDuration,
          maxElectionTime: FiniteDuration,
          tickTime: FiniteDuration,
          memberDict: Map[Int, String],
          name: String)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(
      props(id, minElectionTime, maxElectionTime, tickTime, memberDict),
      name
    )
  }

  def run(serverConf: ServerConf)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(serverConf), "svr-" + serverConf.id)
  }
}

class Server(val id: Int,
             minElectionTime: FiniteDuration,
             maxElectionTime: FiniteDuration,
             tickTime: FiniteDuration,
             memberDict: Map[Int, String])
    extends Actor
    with Logging {

  def this(conf: ServerConf) =
    this(conf.id, conf.minElectionTime, conf.maxElectionTime, conf.tickTime, conf.memberDict)

  import context._
  implicit val scheduler: Scheduler = system.scheduler
  override val logPrefix: String = s"[$id]"

  var curTerm = 0
  var curState: State.Value = State.Unknown
  var curLeader: Option[Int] = None
  var votedFor: Option[Int] = None
  var members: Map[Int, ActorRef] = Map()

  // TODO: Add buffer class (improve log and encapsulation, here maybe)
  val rpcMessageCache: ArrayBuffer[(ActorRef, RPCMessage)] = new ArrayBuffer()
  val clientMessageCache: ArrayBuffer[(ActorRef, ClientMessage)] = new ArrayBuffer()

  val electionTimer = new RandomizedTimer(minElectionTime, maxElectionTime, StartElection)
  val heartBeatTimer = new PeriodicTimer(tickTime, Tick)
  val logs: ArrayBuffer[LogEntry] = new ArrayBuffer[LogEntry]()

  // TODO: Member Management problem, member is added one by one
  memberDict foreach {
    case (serverId, path) =>
      val timeout = 5 seconds
      val f = context
        .actorSelection(path)
        .resolveOne(timeout)
        .map(ref => Resolved(serverId, ref))
        .recover {
          case e =>
            warn(s"Can not find server under path: $path")
            throw e // TODO: use Status.Failure, need to return an exception which will be embraced in Status.Failure
        }
      f pipeTo self
  }

  override def preStart(): Unit = {}

  override def postStop(): Unit = {
    info(s"Server $id stops and cancel all timer scheduled tasks")

    /**
     * Avoid dead letter message
     */
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

  def distributeRPC(msg: RPCMessage, retries: Int = 1): Unit = {
    implicit val timeout = Timeout(tickTime / retries)
    Future
      .sequence {
        for {
          (id, follower) <- members.toSeq if id != this.id
        } yield {
          retry((follower ? msg).mapTo[RPCResult],
                Duration.Zero,
                retries,
                s"Can not reach ${follower.path}, retrying ...")
            .map(x => Success(x))
            .recover { case e => Failure(e) }
            .map(resp => (follower, resp))
        }
      }
      .map { res =>
        CallBack(msg, res)
      } pipeTo self
  }

  def processReplies(results: Seq[(ActorRef, Try[RPCResult])])(
    majorityHandler: Int => Unit): Unit = {
    val requestName =
      if (curState == State.Leader) AppendEntriesResult.getClass.getSimpleName.stripSuffix("$")
      else if (curState == State.Candidate)
        RequestVoteResult.getClass.getSimpleName.stripSuffix("$")
      else throw new Exception("Follower must not process request results")

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
                "The term of reply received must not be smaller than is current term")
            }
          case Failure(e) =>
            warn(s"Can not get $requestName from ${follower.path} with error: " + e)
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
          s"Only minority is reached ($validReplyCount / ${members.size}), " +
            s"a new round will be launched")
      }
    }
    info(s"=== end of processing $requestName ===")
  }

  def flushMessageCache[T <: Message](buffer: ArrayBuffer[(ActorRef, T)]): Unit = {
    if (buffer.isEmpty) {
      info("Message buffer is empty, no flushing")
    } else {
      info(s"Flush messages in buffer with ${buffer.size} message in it")
      buffer foreach { case (src, msg) => self.tell(msg, src) }
      buffer.clear
    }
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

  override def receive: Receive = {
    case Resolved(serverId, ref) =>
      info(s"Find initial member with id=$serverId at ${ref.path}")
      members = members.updated(serverId, ref)
      if (members.size == memberDict.size) {
        becomeFollower(curTerm)
      }
    case msg: RPCMessage =>
      rpcMessageCache.append((sender(), msg))
    case msg: ClientMessage =>
      clientMessageCache.append((sender(), msg))
  }

  def follower: Receive = {
    case cmd: ClientMessage =>
      curLeader match {
        case Some(leaderId) =>
          val leaderRef =
            members.getOrElse(leaderId, throw new Exception("Unknown leader id: " + leaderId))
          leaderRef forward cmd
        case None =>
          info("Leader has not been elected, command cached")
          clientMessageCache.append((sender(), cmd))
      }
    // Timer Event
    case StartElection => becomeCandidate()
    // Leader's request
    case AppendEntries(term, leadId, _, _, _, _) =>
      if (curTerm > term) {
        sender ! AppendEntriesResult(curTerm, success = false)
      } else {
        curTerm = term
        votedFor = None
        // curLeader can not be the sender since this is an endpoint for ask pattern,
        // so the sender is just a temp actor. Need to figure out leader from AppendEntries request
        curLeader = Some(leadId) // maintain authority
        flushMessageCache(clientMessageCache)
        sender ! AppendEntriesResult(curTerm, success = true)
        electionTimer.restart() // only valid heartbeat refresh election timer
      }
    // Candidate's request
    case RequestVote(term, cand, lastIndex, lastTerm) =>
      if (curTerm > term) {
        sender ! RequestVoteResult(curTerm, success = false)
      } else { // when curTerm <= term
        curTerm = term
        if (votedFor.isEmpty || votedFor.get == cand) {
          votedFor = Some(cand)
          sender ! RequestVoteResult(curTerm, success = true)
          electionTimer.restart()
        } else {
          sender ! RequestVoteResult(curTerm, success = false)
        }
      }
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender().path}")
    // throw new IrrelevantMessageException(others, sender)
  }

  def candidate: Receive = {
    case cmd: ClientMessage =>
      curLeader match {
        case Some(_) =>
          throw new Exception("Leader should be empty for candidate")
        case None =>
          info("Leader has not been elected, command cached")
          clientMessageCache.append((sender(), cmd))
      }
    case CallBack(request: RequestVote, responses) =>
      processReplies(responses) { majCnt =>
        info(
          s"Election for term $curTerm is ended since majority is reached " +
            s"($majCnt / ${members.size})")
        becomeLeader()
      }
    // Timer Event
    case StartElection => becomeCandidate()
    // New leader's request
    case AppendEntries(term, leadId, prevLogIndex, prevLogTerm, entries, leaderCommit) =>
      if (curTerm > term) {
        // rejects RPC and continues in candidate state
        sender ! AppendEntriesResult(curTerm, success = false)
      } else { // new leader detected
        sender ! AppendEntriesResult(term, success = true)
        info(s"New leader detected during election by receiving heartbeat with larger term $term")
        becomeFollower(term)
      }
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender().path}")
    // throw new IrrelevantMessageException(others, sender)
  }

  def leader: Receive = {
    case cmd: Command =>
      processClientRequest(cmd)
      sender() ! CommandAccepted()
    case CallBack(request: AppendEntries, responses) =>
      processReplies(responses) { majCnt =>
        info(
          s"Heartbeat for term $curTerm is ended since majority is reached " +
            s"($majCnt / ${members.size}), committing logs")
      }
    // Timer Event
    case Tick => issueHeartbeat()
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender().path}")
    // throw new IrrelevantMessageException(others, sender)
  }

  def becomeFollower(newTerm: Int): Unit = {
    val oldState = curState
    curTerm = newTerm
    curState = State.Follower
    become(follower)
    info(s"Switch from $oldState to follower, current term is $curTerm")
    heartBeatTimer.stop()
    electionTimer.restart()
    flushMessageCache(rpcMessageCache)
  }

  def becomeCandidate(): Unit = {
    curState = State.Candidate
    curTerm = curTerm + 1
    votedFor = Some(id)
    curLeader = None //TODO: Test
    become(candidate)
    info(s"Election for term $curTerm started, server $id becomes candidate")
    electionTimer.restart()
    issueVoteRequest()
  }

  def becomeLeader(): Unit = {
    curState = State.Leader
    curLeader = Some(id)
    become(leader)
    info(s"Server $id becomes leader")
    electionTimer.stop()
    heartBeatTimer.restart()
    flushMessageCache(clientMessageCache)
  }

}
