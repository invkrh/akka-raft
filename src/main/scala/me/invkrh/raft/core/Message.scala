package me.invkrh.raft.core

import scala.util.Try

import akka.actor.ActorRef

object Message {
  
  trait RaftMessage

  trait EventMessage extends RaftMessage
  case object Tick extends EventMessage
  case object StartElection extends EventMessage
  case class Init(memberDict: Map[Int, ActorRef]) extends EventMessage

  trait ClientMessage extends RaftMessage
  case class Command(key: String, value: Int) extends ClientMessage {
    override def toString: String = s"$key = $value"
  }
  case class CommandResponse(success: Boolean, info: String = "")
  
  case class LogEntry(term: Int, command: Command)
  
  // internal RPC message
  trait RPCMessage extends RaftMessage {
    def term: Int
  }
  case class AppendEntries(term: Int,
                           leadId: Int,
                           prevLogIndex: Int,
                           prevLogTerm: Int,
                           entries: Seq[LogEntry],
                           leaderCommit: Int)
      extends RPCMessage
  case class RequestVote(term: Int, candidateId: Int, lastLogIndex: Int, lastLogTerm: Int)
      extends RPCMessage

  // internal RPC response message
  trait RPCResult extends RPCMessage {
    def success: Boolean
  }
  case class AppendEntriesResult(term: Int, success: Boolean) extends RPCResult
  case class RequestVoteResult(term: Int, success: Boolean) extends RPCResult
  
  case class CallBack(request: RPCMessage, responses: Seq[(ActorRef, Try[RPCResult])])
      extends RaftMessage
}
