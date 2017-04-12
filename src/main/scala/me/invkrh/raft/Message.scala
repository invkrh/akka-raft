package me.invkrh.raft

import akka.actor.ActorRef

object Message {
  
  // Admin message
  case class Join(servers: ActorRef*)
  
  // Raft message
  trait RPCMessage {
    def term: Int
  }
  
  case class Command(key: String, value: Int)
  case class Entry(term: Int, command: Command)
  
  case class AppendEntries(term: Int,
                           leadId: Int,
                           prevLogIndex: Int,
                           prevLogTerm: Int,
                           entries: Seq[Entry],
                           leaderCommit: Int)
      extends RPCMessage
  case class RequestVote(term: Int, candidateId: Int, lastLogIndex: Int, lastLogTerm: Int)
      extends RPCMessage

  case class AppendEntriesResult(term: Int, success: Boolean) extends RPCMessage
  case class RequestVoteResult(term: Int, voteGranted: Boolean) extends RPCMessage
}
