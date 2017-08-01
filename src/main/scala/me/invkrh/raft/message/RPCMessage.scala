package me.invkrh.raft.message

sealed trait RPCRequest extends RaftMessage
case class AppendEntries(
  term: Int,
  leaderId: Int,
  prevLogIndex: Int,
  prevLogTerm: Int,
  entries: List[LogEntry],
  leaderCommit: Int
) extends RPCRequest
case class RequestVote(term: Int, candidateId: Int, lastLogIndex: Int, lastLogTerm: Int)
    extends RPCRequest

sealed trait RPCResponse extends RaftMessage {
  def term: Int
  def success: Boolean
}
case class RequestVoteResult(term: Int, success: Boolean) extends RPCResponse
case class AppendEntriesResult(term: Int, success: Boolean) extends RPCResponse
case class RequestTimeout(term: Int) extends RPCResponse {
  override def success: Boolean = false
}

case class Exchange(request: RPCRequest, response: RPCResponse, followerId: Int)
trait Conversation {
  def content: Iterable[Exchange]
}
case class AppendEntriesConversation(content: Iterable[Exchange]) extends Conversation
case class RequestVoteConversation(content: Iterable[Exchange]) extends Conversation
