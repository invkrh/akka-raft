package me.invkrh.raft

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{Actor, ActorRef, Props, Scheduler}
import me.invkrh.raft.RPCMessage._
import me.invkrh.raft.util.{Logging, TimeOut}

object Server {
  def props(id: Int,
            electionTimeout: FiniteDuration,
            heartBeat: FiniteDuration = 1 seconds,
            members: ArrayBuffer[ActorRef] = new ArrayBuffer[ActorRef]()) =
    Props(new Server(id, electionTimeout, heartBeat, members))
}

class Server(id: Int,
             electionTimeout: FiniteDuration,
             heartBeatTimeout: FiniteDuration,
             members: ArrayBuffer[ActorRef])
    extends Actor
    with Logging {

  import context._
  implicit val scheduler: Scheduler = context.system.scheduler

  val electionTimer = new TimeOut(electionTimeout, startElection())
  val heartBeatTimer = new TimeOut(heartBeatTimeout, dispatchHeartBeat())

  var curTerm = 0
  var votedFor: Option[Int] = None
  val log: ArrayBuffer[Entry] = new ArrayBuffer[Entry]()

  var state = State.Follower

  def distributeRPC(msg: Request): Unit = {
    members.par.filter(_ != this) foreach (_ ! msg)
  }

  def startElection(): Unit = {
    state = State.Candidate
    curTerm = curTerm + 1
    votedFor = Some(id)
    info(s"Election for term $curTerm started, server $id becomes $state")
    become(candidate)
    electionTimer.reset()
    distributeRPC(RequestVote(curTerm, id, 0, 0))
  }

  def dispatchHeartBeat(): Unit = {
    distributeRPC(AppendEntries(curTerm, id, 0, 0, Seq(), 0))
  }

  def follower: Receive = {

    case AppendEntries(term, _, _, _, _, _) =>
      electionTimer.reset()
      if (curTerm > term) {
        sender ! AppendEntriesResult(curTerm, success = false)
      } else {
        curTerm = term
        sender ! AppendEntriesResult(curTerm, success = true)
      }
    case RequestVote(term, cand, lastIndex, lastTerm) =>
      if (curTerm > term) {
        sender ! RequestVoteResult(curTerm, voteGranted = false)
      } else { // when curTerm <= term
        curTerm = term
        if (votedFor.isEmpty) {
          votedFor = Some(cand)
          sender ! RequestVoteResult(curTerm, voteGranted = true)
          electionTimer.reset()
        } else {
          sender ! RequestVoteResult(curTerm, voteGranted = false)
        }
      }
  }

  var voteCount = 1
  def candidate: Receive = {
    case RequestVoteResult(term, voteGranted) =>
      if (curTerm > term) {
        // Do nothing
      } else if (curTerm < term) { // term is outdated
        voteCount = 1
        become(follower)
      } else { // When curTerm == term
        if (voteGranted) {
          voteCount = voteCount + 1
          if (voteCount > members.size / 2) {
            voteCount = 1
            state = State.Leader
            info(s"Election for term $curTerm is ended, server $id becomes $state")
            become(leader)
            electionTimer.stop()
            heartBeatTimer.reset()
          }
        }
      }

    case AppendEntries(term, leadId, prevLogIndex, prevLogTerm, entries, leaderCommit) =>
      if (curTerm > term) {
        // rejects RPC and continues in candidate state
        sender ! AppendEntriesResult(curTerm, success = false)
      } else { // new leader detected
        curTerm = term
        become(follower)
      }
  }

  def leader: Receive = {

    case AppendEntriesResult(term, success) =>
      if (curTerm > term) {
        // message from old term
      } else if (curTerm < term) { // term is outdated
        curTerm = term
        become(follower)
      } else {
        // When curTerm == term, good here, do nothing
      }
  }

  override def preStart(): Unit = {
    if (!members.contains(self)) {
      members += self
    }
    info(s"Start as $state, intial term is $curTerm with election timeout $electionTimeout")
    electionTimer.reset()
  }

  override def postStop(): Unit = {
    info(s"Server $id stops and cancel all timer tasks")

    /**
     * Note: if there are still some timer task when actor stopped (system stop),
     * an error will be thrown. Need to stop all timer here.
     */
    electionTimer.stop()
  }

  // Default to follower
  override def receive: Receive = follower
}
