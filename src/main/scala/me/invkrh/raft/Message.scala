package me.invkrh.raft

import akka.actor.ActorRef

object RPCMessage {
  
  
  case class Command(key: String, value: Int)
  case class Entry(term: Int, command: Command)
  
  
  trait Message {
    def term: Int
  }
  case class AppendEntries(term: Int,
                           leadId: Int,
                           prevLogIndex: Int,
                           prevLogTerm: Int,
                           entries: Seq[Entry],
                           leaderCommit: Int)
      extends Message
  case class RequestVote(term: Int, candidateId: Int, lastLogIndex: Int, lastLogTerm: Int)
      extends Message

  case class AppendEntriesResult(term: Int, success: Boolean) extends Message
  case class RequestVoteResult(term: Int, voteGranted: Boolean) extends Message
}
