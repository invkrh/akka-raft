package me.invkrh.raft

import scala.collection.{immutable, mutable}
import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.mutable.ParSeq
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

import akka.actor.{Actor, ActorRef, Props, Scheduler}
import akka.pattern.ask
import akka.util.Timeout
import me.invkrh.raft.Exception._
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
    with IdentifyLogging {

  import context._
  implicit val scheduler: Scheduler = context.system.scheduler

  val electionTimer = new FixedTimer(electionTimeoutInMillis, startElection())
  //TODO: Think of RandomizedTimer (polymophism)
  val heartBeatTimer = new PeriodicTimer(heartBeatIntervalInMillis, issueHeartbeat())
//  val heartBeatTimer = new PeriodicTimer(heartBeatIntervalInMillis, distributeHeartBeat())

  val members: mutable.Set[ActorRef] = mutable.Set()

  val log: ArrayBuffer[Entry] = new ArrayBuffer[Entry]()
  var curTerm = 0
  var votedFor: Option[Int] = None

  def tellCluster(msg: RPCMessage): Unit = {
    members.par.filter(_ != self) foreach (_ ! msg)
  }

//  def distributeHeartBeat(): Unit = {
//    tellCluster(AppendEntries(curTerm, id, 0, 0, Seq(), 0))
//  }

  import akka.pattern.after

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
               retryMsg: String = ""): Future[T] =
    ft recoverWith {
      case _ if retries > 0 =>
        warn(retryMsg)
        after(delay, scheduler)(retry(ft, delay, retries - 1, retryMsg))
    }

  def futureToFutureTry[T](f: Future[T]): Future[Try[T]] =
    f.map(Success(_))
      .recover { case e => Failure(e) }

  def issueRPC[R: ClassTag](msg: RPCMessage): Future[Seq[(ActorRef, Try[R])]] = {
    val retries = 2
    implicit val timeout = Timeout(heartBeatIntervalInMillis / retries milliseconds)

    Future.sequence {
      (for {
        follower <- members.toSeq.par if follower != self
      } yield {
        futureToFutureTry(
          retry(follower ? msg,
                Duration.Zero,
                retries,
                s"Can not reach ${follower.path}, retrying ...").mapTo[R]).map(resp =>
          (follower, resp))
      }).seq
    }
  }

  def issueVoteRequest(): Unit = {
    issueRPC[RequestVoteResult](RequestVote(curTerm, id, 0, 0)).onComplete {
      case Success(results) =>
        val (followerAndResponse, followerAndException) = results.partition(_._2.isSuccess) match {
          case (successes, failures) =>
            (successes.map {
              case (fol, t) => (fol, t.get)
            }, failures.map {
              case (fol, Failure(e)) => (fol, e)
              case _ => throw new Exception("")
            })
        }

        var newLeaderTerm = 0
        var grantedVotes = 1 // candidate votes for himself
        followerAndResponse foreach {
          case (follower, RequestVoteResult(term, granted)) =>
            if (!granted) {
              warn(s"RequestVote to ${follower.path} with term $term is rejected")
            } else {
              if (curTerm < term) {
                info(s"New leader is detected by ${follower.path} with term $term")
                newLeaderTerm = term
              } else {
                info(s"Receive vote granted from ${follower.path} with term $term")
                grantedVotes += 1
              }
            }
        }

        followerAndException foreach {
          case (follower, e) =>
            warn(s"Can not get RequestVoteResult from ${follower.path} with error: " + e)
        }

        val header = "Vote Request Results =>"
        if (newLeaderTerm > 0) {
          info(s"$header Switch from candidate to follower")
          becomeFollower(newLeaderTerm)
        } else {
          if (grantedVotes > members.size / 2) {
            info(
              s"$header Election for term $curTerm is ended since majority is reached " +
              s"($grantedVotes / ${members.size}), server $id becomes leader")
            becomeLeader()
          } else {
            info(
              s"$header Only minority is reached ($grantedVotes / ${members.size}), " +
              s"election for next term (${curTerm + 1}) will launched")
          }
        }

        info("=== End of vote request batch ===")

      case Failure(e) =>
        error("System error: all heartbeats are gone, see details: " + e)
        throw e
    }
  }

  def issueHeartbeat(): Unit = {
    issueRPC[AppendEntriesResult](AppendEntries(curTerm, id, 0, 0, Seq(), 0)).onComplete {
      case Success(results) =>
        val (followerAndResponse, followerAndException) = results.partition(_._2.isSuccess) match {
          case (successes, failures) =>
            (successes.map {
              case (fol, t) => (fol, t.get)
            }, failures.map {
              case (fol, Failure(e)) => (fol, e)
              case _ => throw new Exception("")
            })
        }

        var newLeaderTerm = 0
        followerAndResponse foreach {
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

        followerAndException foreach {
          case (follower, e) =>
            warn(s"Can not get RequestVoteResult from ${follower.path} with error: " + e)
        }

        val header = "Heartbeat ACK Results =>"
        if (newLeaderTerm > 0) {
          info(s"$header Switch from leader to follower")
          becomeFollower(newLeaderTerm)
        }

        info("=== End of vote request batch ===")

      case Failure(e) =>
        error("System error: all heartbeats are gone, see details: " + e)
        throw e
    }
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
      } else { // new leader detected, since term is at least as large as the current term
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
    // tellCluster(RequestVote(curTerm, id, 0, 0))
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
