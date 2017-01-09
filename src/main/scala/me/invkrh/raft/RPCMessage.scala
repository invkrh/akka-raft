package me.invkrh.raft

object RPCMessage {
  trait Entry
  case class AppendEntries(term: Int,
                           leadId: String,
                           prevLogIndex: Int,
                           prevLogTerm: Int,
                           entries: Seq[Entry],
                           leaderCommit: Int)
  case class AppendEntriesResult(term: Int, success: Boolean)
  case class RequestVote(term: Int, candidateId: String, lastLogIndex: Int, lastLogTerm: Int)
  case class RequestVoteResult(term: Int, voteGranted: Boolean)
}
