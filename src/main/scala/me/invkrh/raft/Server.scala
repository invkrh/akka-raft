package me.invkrh.raft

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps

import akka.actor.{Actor, ActorRef, Props, Scheduler}
import me.invkrh.raft.Message._
import Exception._
import me.invkrh.raft.util.{FixedTimer, IdentifyLogging}

object Server {
  def props(id: Int,
            electionTimeoutInMillis: Int,
            heartBeatIntervalInMillis: Int): Props = {
    require(electionTimeoutInMillis > heartBeatIntervalInMillis)
    Props(new Server(id, electionTimeoutInMillis, heartBeatIntervalInMillis))
  }
}

class Server(val id: Int,
             electionTimeoutInMillis: Int,
             heartBeatTimeoutInMillis: Int)
    extends Actor
    with IdentifyLogging {

  import context._
  implicit val scheduler: Scheduler = context.system.scheduler

  val electionTimer                  = new FixedTimer(electionTimeoutInMillis, startElection())
  val heartBeatTimer                 = new FixedTimer(heartBeatTimeoutInMillis, dispatchHeartBeat())
  val members: mutable.Set[ActorRef] = mutable.Set()

  // TODO: Add member list function
  val log: ArrayBuffer[Entry] = new ArrayBuffer[Entry]()
  var curTerm = 0
  var votedFor: Option[Int] = None
  var state = State.Follower
  var voteGrantedNum = 1

  def startElection(): Unit = {
    state = State.Candidate
    curTerm = curTerm + 1
    votedFor = Some(id)
    info(s"Election for term $curTerm started, server $id becomes $state")
    become(candidate)
    distributeRPC(RequestVote(curTerm, id, 0, 0))
    electionTimer.restart()
  }
  
  def distributeRPC(msg: RPCMessage): Unit = {
    members.par.filter(_ != self) foreach (_ ! msg)
  }
  
  def dispatchHeartBeat(): Unit = {
    distributeRPC(AppendEntries(curTerm, id, 0, 0, Seq(), 0))
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
    case others: RPCMessage => throw new IrrelevantMessageException(others, sender)
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
    case others: RPCMessage => new IrrelevantMessageException(others, sender)
  }

  def leader: Receive = {
    case AppendEntriesResult(term, true) =>
      if (curTerm > term) {
        warn(s"Leader received obsolete AppendEntriesResult with term $term")
      } else if (curTerm < term){
        become(follower)
      } else { // curTerm == term
        // TODO: process log
      }
    // Irrelevant messages
    case others: RPCMessage => new IrrelevantMessageException(others, sender)
  }
  
  // Membership view initialization
  override def receive: Receive = {
    case init: Join =>
      members ++= init.servers
      become(follower)
  }
 
  override def preStart(): Unit = {
    if (!members.contains(self)) {
      members += self
    }
    info(
      s"Start as $state, intial term is $curTerm with election timeout $electionTimeoutInMillis ms")
    electionTimer.restart()
  }

  override def postStop(): Unit = {
    info(s"Server $id stops and cancel all timer tasks")

    /**
     * Note: if there are still some timer task when actor stopped (system stop),
     * an error will be thrown. Need to stop all timer here.
     */
    electionTimer.stop()
  }

}
