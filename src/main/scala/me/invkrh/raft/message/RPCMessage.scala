package me.invkrh.raft.message

import akka.actor.ActorRef

sealed trait RPCRequest extends RaftMessage
case class AppendEntries(
  term: Int,
  leaderId: Int,
  prevLogIndex: Int,
  prevLogTerm: Int,
  entries: Seq[LogEntry],
  leaderCommit: Int
) extends RPCRequest
case class RequestVote(term: Int, candidateId: Int, lastLogIndex: Int, lastLogTerm: Int)
    extends RPCRequest

sealed trait RPCResponse extends RaftMessage
case class RequestVoteResult(term: Int, voteGranted: Boolean) extends RPCResponse
case class AppendEntriesResult(term: Int, success: Boolean) extends RPCResponse
case class RequestTimeout(term: Int) extends RPCResponse

case class Exchange(
  request: RPCRequest,
  response: RPCResponse,
  followerId: Int,
  followerRef: ActorRef
)
case class AppendEntriesConversation(content: Iterable[Exchange])
case class RequestVoteConversation(content: Iterable[Exchange])
