package me.invkrh.raft

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
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
  val heartBeatTimer = new PeriodicTimer(heartBeatIntervalInMillis, distributeHeartBeat())

  val members: mutable.Set[ActorRef] = mutable.Set()

  // TODO: Add member list function
  val log: ArrayBuffer[Entry] = new ArrayBuffer[Entry]()
  var curTerm = 0
  var votedFor: Option[Int] = None
  var state = State.Follower
  var voteGrantedNum = 1

  def tellCluster(msg: RPCMessage): Unit = {
    members.par.filter(_ != self) foreach (_ ! msg)
  }

  def askCluster[T: ClassTag](msg: RPCMessage): Future[mutable.Set[(ActorRef, Try[T])]] = {
    implicit val timeout = Timeout(heartBeatIntervalInMillis / 2 seconds)
    def futureToFutureTry[T](f: Future[T]): Future[Try[T]] =
      f.map(Success(_)).recover { case e => Failure(e) }
    Future.sequence {
      (for {
        s <- members.par if s != self
      } yield {
        futureToFutureTry((s ? msg).mapTo[T]).map(resp => (s, resp))
      }).seq
    }
  }

  def distributeHeartBeat(): Unit = {
    tellCluster(AppendEntries(curTerm, id, 0, 0, Seq(), 0))
  }

  def distributeHeartBeatWithAck(): Unit = {
    askCluster[AppendEntriesResult](AppendEntries(curTerm, id, 0, 0, Seq(), 0)).onComplete {
      case Success(responseSet) =>
        // TODO: specify response statics
        val acks = responseSet.map {
          case (follower, reply) =>
            val errorReason = reply match {
              case Success(AppendEntriesResult(t, true)) if t == curTerm => None
              case Success(AppendEntriesResult(t, false)) if t == curTerm =>
                Some("Heartbeat rejected")
              case Success(AppendEntriesResult(t, _)) if t != curTerm =>
                Some("New leader detected") // t is at least as large as curTerm
              case Failure(e) => Some("Can not get ack from follower, clue: " + e)
            }
            (follower, errorReason)
        }

        if (acks.forall(_._2.isEmpty)) {
          info("All members are consistent")
        } else {
          val errorMsgs = acks
            .flatMap {
              case (followerRef, Some(reason)) => Some(s"$followerRef => $reason")
              case (followerRef, None) => None
            }
            .mkString("\n")
          warn("Some followers are in inconsistent stat: " + errorMsgs)
        }
      case Failure(e) => error("None of heartbeats are received, error: " + e)
    }
  }

  def startElection(): Unit = {
    state = State.Candidate
    curTerm = curTerm + 1
    votedFor = Some(id)
    info(s"Election for term $curTerm started, server $id becomes $state")
    become(candidate)
    tellCluster(RequestVote(curTerm, id, 0, 0))
    electionTimer.restart()
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
        if (votedFor.isEmpty) {
          votedFor = Some(cand)
          sender ! RequestVoteResult(curTerm, voteGranted = true)
          electionTimer.restart()
        } else {
          sender ! RequestVoteResult(curTerm, voteGranted = false)
        }
      }
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender()}")
      // throw new IrrelevantMessageException(others, sender)
  }

  def candidate: Receive = {
    case RequestVoteResult(term, voteGranted) =>
      if (curTerm > term) {
        warn(s"Candidate received obsolete RequestVoteResult with term $term")
      } else if (curTerm < term) { // current term is outdated, a new leader was elected
        // reinitialise vote count
        voteGrantedNum = 1
        curTerm = term
        become(follower)
        info(s"New leader detected, server $id become follower")
      } else { // curTerm == term)
        if (voteGranted) {
          info(s"Received vote granted message from ${sender.path}")
          voteGrantedNum = voteGrantedNum + 1
          if (voteGrantedNum > members.size / 2) {
            voteGrantedNum = 1
            state = State.Leader
            info(s"Election for term $curTerm is ended, server $id becomes $state")
            become(leader)
            electionTimer.stop()
            heartBeatTimer.restart()
          }
        }
      }

    case AppendEntries(term, leadId, prevLogIndex, prevLogTerm, entries, leaderCommit) =>
      if (curTerm > term) {
        // rejects RPC and continues in candidate state
        sender ! AppendEntriesResult(curTerm, success = false)
      } else { // new leader detected, since term is at least as large as the current term
        curTerm = term
        sender ! AppendEntriesResult(curTerm, success = true)
        become(follower)
      }
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender()}")
      // throw new IrrelevantMessageException(others, sender)
  }

  def leader: Receive = {
    case AppendEntriesResult(term, true) =>
      if (curTerm > term) {
        warn(s"Leader received obsolete AppendEntriesResult with term $term")
      } else if (curTerm < term) {
        curTerm = term
        become(follower)
        heartBeatTimer.stop()
        electionTimer.restart()
      } else { // curTerm == term
        // TODO: process log
        info("Receive heartbeat from " + sender())
      }
    // Irrelevant messages
    case others: RPCMessage =>
      warn(s"Irrelevant message [$others] received from ${sender()}")
      // throw new IrrelevantMessageException(others, sender)
  }

  // Membership view initialization
  override def receive: Receive = {
    case init: Join =>
      members ++= init.servers
      become(follower)
      info(
        s"Start as $state, intial term is $curTerm with election timeout $electionTimeoutInMillis ms")
      electionTimer.restart()
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
