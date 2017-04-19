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
import me.invkrh.raft.Message._
import me.invkrh.raft.util.{FixedTimer, IdentifyLogging, PeriodicTimer}

object Server {
  def props(id: Int, electionTimeoutInMillis: Int, heartBeatIntervalInMillis: Int): Props = {
    require(electionTimeoutInMillis > heartBeatIntervalInMillis)
    Props(new Server(id, electionTimeoutInMillis, heartBeatIntervalInMillis))
  }
}

class Server(val id: Int, electionTimeoutInMillis: Int, heartBeatIntervalInMillis: Int)
    extends Actor
    with ActorLogging
    with IdentifyLogging {

  import context._
  implicit val scheduler: Scheduler = context.system.scheduler
  
  type TryRes[R] = (ActorRef, Try[R])
  type SucRes[R] = (ActorRef, R)
  type FalRes = (ActorRef, Throwable)

  val electionTimer = new FixedTimer(electionTimeoutInMillis, startElection())
  //TODO: Think of RandomizedTimer (polymophism)
  val heartBeatTimer = new PeriodicTimer(heartBeatIntervalInMillis, issueHeartbeat())

  val members: mutable.Set[ActorRef] = mutable.Set()

  val logs: ArrayBuffer[Entry] = new ArrayBuffer[Entry]()
  var curTerm = 0
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


  def distributeRPC[R <: RPCMessage : ClassTag](msg: RPCMessage, retries: Int)(
    resultsHandler: (List[SucRes[R]], List[FalRes]) => Unit): Unit = {
    implicit val timeout = Timeout(heartBeatIntervalInMillis / retries milliseconds)
    Future
      .sequence {
        (for {
          follower <- members.toSeq if follower != self
        } yield {
          retry((follower ? msg).mapTo[R],
                Duration.Zero,
                retries,
                s"Can not reach ${follower.path}, retrying ...")
            .map(Success(_))
            .recover { case e => Failure(e) }
            .map(resp => (follower, resp))
        }).seq
      }
      .onSuccess {
        case results =>
          val (suc, fal) = results.foldLeft(Nil: List[SucRes[R]], Nil: List[FalRes]) {
            case ((replyMsgs, exs), (from, res)) =>
              res match {
                case Success(reply) => ((from, reply) :: replyMsgs, exs)
                case Failure(e) => (replyMsgs, (from, e) :: exs)
              }
          }
          resultsHandler(suc, fal)
      }
  }

  def processRequestVoteResult(successes: List[SucRes[RequestVoteResult]],
                               failures: List[FalRes]): Unit = {
    var newLeaderTerm = 0
    var grantedVotes = 1 // candidate votes for himself
    successes foreach {
      case (follower, RequestVoteResult(term, granted)) =>
        if (!granted) {
          warn(s"RequestVote to ${follower.path} with term $term is rejected")
        } else {
          if (curTerm < term) {
            info(s"New leader is detected by ${follower.path} with term $term")
            newLeaderTerm = Math.max(term, newLeaderTerm)
          } else {
            info(s"Receive vote granted from ${follower.path} with term $term")
            grantedVotes += 1
          }
        }
    }
    failures foreach {
      case (follower, e) =>
        warn(s"Can not get RequestVoteResult from ${follower.path} with error: " + e)
    }
    if (newLeaderTerm > 0) {
      info(s"Switch from candidate to follower")
      becomeFollower(newLeaderTerm)
    } else {
      if (grantedVotes > members.size / 2) {
        info(
          s"Election for term $curTerm is ended since majority is reached " +
            s"($grantedVotes / ${members.size}), server $id becomes leader")
        becomeLeader()
      } else {
        info(
          s"Only minority is reached ($grantedVotes / ${members.size}), " +
            s"election for next term (${curTerm + 1}) will be launched")
      }
    }
    info("=== end of vote request epoch ===")
  }

  def processAppendEntriesResult(successes: List[SucRes[AppendEntriesResult]],
                                 failures: List[FalRes]): Unit = {
    var newLeaderTerm = 0
    successes foreach {
      case (follower, AppendEntriesResult(term, success)) =>
        if (!success) {
          warn(s"AppendEntries to ${follower.path} with term $term is rejected")
        } else {
          if (curTerm < term) {
            info(s"New leader is detected by ${follower.path} with term $term")
            newLeaderTerm = term
          } else {
            info(s"Receive heartbeat ack from ${follower.path} with term $term")
          }
        }
    }
    failures foreach {
      case (follower, e) =>
        warn(s"Can not get AppendEntriesResult from ${follower.path} with error: " + e)
    }
    if (newLeaderTerm > 0) {
      info("Switch from leader to follower")
      becomeFollower(newLeaderTerm)
    }
    info("=== end of heartbeat epoch ===")
  }

  def issueVoteRequest(): Unit = {
    distributeRPC(RequestVote(curTerm, id, 0, 0), 5)(processRequestVoteResult)
  }

  def issueHeartbeat(): Unit = {
    distributeRPC(AppendEntries(curTerm, id, 0, 0, Seq(), 0), 5)(processAppendEntriesResult)
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
        electionTimer.restart() // only valid heartbeat refresh election timer
        sender ! AppendEntriesResult(curTerm, success = true)
      }
    // Candidate's request
    case RequestVote(term, cand, lastIndex, lastTerm) =>
      if (curTerm > term) {
        sender ! RequestVoteResult(curTerm, voteGranted = false)
      } else { // when curTerm <= term
        curTerm = term
        if (votedFor.isEmpty || votedFor.get == cand) {
          votedFor = Some(cand)
          sender ! RequestVoteResult(curTerm, voteGranted = true)
          electionTimer.restart()
        } else {
          sender ! RequestVoteResult(curTerm, voteGranted = false)
        }
      }
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender().path}")
    // throw new IrrelevantMessageException(others, sender)
  }

  def candidate: Receive = {
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
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender().path}")
    // throw new IrrelevantMessageException(others, sender)
  }

  def becomeFollower(newTerm: Int): Unit = {
    curTerm = newTerm
    become(follower)
    heartBeatTimer.stop()
    electionTimer.restart()
  }

  def becomeCandidate(): Unit = {
    become(candidate)
    issueVoteRequest()
    electionTimer.restart()
  }

  def becomeLeader(): Unit = {
    become(leader)
    electionTimer.stop()
    heartBeatTimer.restart()
  }

  // Membership view initialization
  override def receive: Receive = {
    case init: Join =>
      members ++= init.servers
      becomeFollower(curTerm)
      info(
        s"Start as follower, initial term is $curTerm " +
          s"with election timeout $electionTimeoutInMillis ms")
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
