package me.invkrh.raft

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler}
import akka.pattern.{after, ask}
import akka.util.Timeout
import me.invkrh.raft.Message.{RPCResult, _}
import me.invkrh.raft.util.{FixedTimer, IdentifyLogging, PeriodicTimer, RandomizedTimer, Timer}

object Server {
  def props(id: Int,
            electionTimeoutInMillis: Int,
            heartBeatIntervalInMillis: Int,
            withRandomness: Boolean = false): Props = {
    require(electionTimeoutInMillis > heartBeatIntervalInMillis)
    Props(new Server(id, electionTimeoutInMillis, heartBeatIntervalInMillis, withRandomness))
  }
}

class Server(val id: Int,
             electionTimeoutInMillis: Int,
             heartBeatIntervalInMillis: Int,
             withRandomness: Boolean = false)
    extends Actor
    with ActorLogging
    with IdentifyLogging {

  import context._
  implicit val scheduler: Scheduler = context.system.scheduler

  type TryRes[R] = (ActorRef, Try[R])
  type SucRes[R] = (ActorRef, R)
  type FalRes = (ActorRef, Throwable)

  val electionTimer: Timer = if (withRandomness) {
    new RandomizedTimer(electionTimeoutInMillis, electionTimeoutInMillis * 2, startElection())
  } else {
    new FixedTimer(electionTimeoutInMillis, startElection())
  }

  val heartBeatTimer = new PeriodicTimer(heartBeatIntervalInMillis, issueHeartbeat())

  val members: mutable.Set[ActorRef] = mutable.Set()

  val logs: ArrayBuffer[Entry] = new ArrayBuffer[Entry]()
  var curTerm = 0
  var curState: State.Value = State.Follower
  var votedFor: Option[Int] = None

  /**
   * Given an operation that produces a T, returns a Future containing the result of T,
   * unless an exception is thrown, in which case the operation will be retried after _delay_ time,
   * if there are more possible retries, which is configured through the _retries_ parameter.
   * If the operation does not succeed and there is no retries left,
   * the resulting Future will contain the last failure.
   **/
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

  def distributeRPC(msg: RPCMessage, retries: Int = 2): Unit = {
    implicit val timeout = Timeout(heartBeatIntervalInMillis / retries milliseconds)
    Future
      .sequence {
        (for {
          follower <- members.toSeq if follower != self
        } yield {
          retry((follower ? msg).mapTo[RPCResult],
                Duration.Zero,
                retries,
                s"Can not reach ${follower.path}, retrying ...")
            .map(Success(_))
            .recover { case e => Failure(e) }
            .map(resp => (follower, resp))
        }).seq
      }
      /** Warning
       * When using future callbacks, such as onComplete, onSuccess, and onFailure, inside actors you
       * need to carefully avoid closing over the containing actor’s reference, i.e. do not call
       * methods or access mutable state on the enclosing actor from within the callback. This would
       * break the actor encapsulation and may introduce synchronization bugs and race conditions
       * because the callback will be scheduled concurrently to the enclosing actor. Unfortunately
       * there is not yet a way to detect these illegal accesses at compile time.
       */
      .foreach { res =>
        self ! IO(msg, res)
      }
  }

  def processReplies(results: Seq[(ActorRef, Try[RPCResult])])(majorityHandler: Int => Unit) = {
    val requestName =
      if (curState == State.Leader) AppendEntriesResult.getClass.getSimpleName.stripSuffix("$")
      else if (curState == State.Candidate) RequestVoteResult.getClass.getSimpleName.stripSuffix("$")
      else throw new Exception("Follower must not process request results")

    val (maxTerm, validReplyCount) = results.foldLeft(curTerm, 1) {
      case ((curMaxTerm, count), (follower, tryRes)) =>
        tryRes match {
          case Success(res) =>
            if (res.term > curTerm) {
              info(s"New leader is detected by ${follower.path} with term ${res.term}")
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
      info(s"Switch from $curState to follower")
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

  def issueVoteRequest(): Unit = {
    distributeRPC(RequestVote(curTerm, id, 0, 0))
  }

  def issueHeartbeat(): Unit = {
    distributeRPC(AppendEntries(curTerm, id, 0, 0, Seq(), 0))
  }

  def startElection(): Unit = {
    curTerm = curTerm + 1
    votedFor = Some(id)
    info(s"Election for term $curTerm started, server $id becomes candidate")
    becomeCandidate()
  }

  def follower: Receive = {
    // Leader's request
    case AppendEntries(term, _, _, _, _, _) =>
      if (curTerm > term) {
        sender ! AppendEntriesResult(curTerm, success = false)
      } else {
        curTerm = term
        votedFor = None
        electionTimer.restart() // only valid heartbeat refresh election timer
        sender ! AppendEntriesResult(curTerm, success = true)
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
    case IO(_, replies) =>
      processReplies(replies) { majCnt =>
        info(
          s"Election for term $curTerm is ended since majority is reached " +
            s"($majCnt / ${members.size}), server $id becomes leader")
        becomeLeader()
      }
    case AppendEntries(term, leadId, prevLogIndex, prevLogTerm, entries, leaderCommit) =>
      if (curTerm > term) {
        // rejects RPC and continues in candidate state
        sender ! AppendEntriesResult(curTerm, success = false)
      } else { // new leader detected
        sender ! AppendEntriesResult(term, success = true)
        info("New leader detected, switch from candidate to follower")
        becomeFollower(term)
      }
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender().path}")
    // throw new IrrelevantMessageException(others, sender)
  }

  def leader: Receive = {
    // TODO: Add admin endpoint and follower -> leader redirection
    case IO(request, replies) =>
      processReplies(replies) { majCnt =>
        info(
          s"Election for term $curTerm is ended since majority is reached " +
            s"($majCnt / ${members.size}), committing logs")
      }
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender().path}")
    // throw new IrrelevantMessageException(others, sender)
  }

  def becomeFollower(newTerm: Int): Unit = {
    curTerm = newTerm
    curState = State.Follower
    become(follower)
    heartBeatTimer.stop()
    electionTimer.restart()
  }

  def becomeCandidate(): Unit = {
    curState = State.Candidate
    become(candidate)
    issueVoteRequest()
    electionTimer.restart()
  }

  def becomeLeader(): Unit = {
    curState = State.Leader
    become(leader)
    electionTimer.stop()
    heartBeatTimer.restart()
  }

  // Membership view initialization
  override def receive: Receive = {
    case init: Join =>
      members ++= init.servers
      becomeFollower(curTerm)
      info(s"Start as follower, initial term is $curTerm ")
  }

  override def preStart(): Unit = {
    members += self
  }

  override def postStop(): Unit = {
    info(s"Server $id stops and cancel all timer tasks")

    /**
     * Note: if there are still some timer task when actor stopped (system stop),
     * an error will be thrown. Need to stop all timer here.
     */
    electionTimer.stop()
    heartBeatTimer.stop()
  }

}
