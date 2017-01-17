package me.invkrh.raft

import akka.actor.ActorRef

object RPCMessage {
  trait Request
  case class Command(key: String, value: Int)
  case class Entry(term: Int, command: Command)

  case class AppendEntries(term: Int,
                           leadId: Int,
                           prevLogIndex: Int,
                           prevLogTerm: Int,
                           entries: Seq[Entry],
                           leaderCommit: Int)
      extends Request
  case class RequestVote(term: Int, candidateId: Int, lastLogIndex: Int, lastLogTerm: Int)
      extends Request
  case class Join(ref: ActorRef) extends Request
  case class Leave(ref: ActorRef) extends Request

  case class AppendEntriesResult(term: Int, success: Boolean)
  case class RequestVoteResult(term: Int, voteGranted: Boolean)
}
