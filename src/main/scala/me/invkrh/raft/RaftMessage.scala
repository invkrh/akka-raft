package me.invkrh.raft

import scala.util.Try

import akka.actor.ActorRef

object RaftMessage {

  // admin message
  trait Event
  case object Tick extends Event
  case object StartElection extends Event
  case class Resolved(serverId: Int, serverRef: ActorRef, total: Int) extends Event

  trait Message

  // client message
  trait ClientMessage extends Message
  case class Command(key: String, value: Int) extends ClientMessage
  case class CommandAccepted() extends ClientMessage
  case class CommandRejected(info: String) extends ClientMessage

  // internal RPC message
  trait RPCMessage extends Message {
    def term: Int
  }
  case class AppendEntries(term: Int,
                           leadId: Int,
                           prevLogIndex: Int,
                           prevLogTerm: Int,
                           entries: Seq[Entry],
                           leaderCommit: Int)
      extends RPCMessage
  case class RequestVote(term: Int, candidateId: Int, lastLogIndex: Int, lastLogTerm: Int)
      extends RPCMessage
  
  // internal RPC message response
  trait RPCResult extends RPCMessage {
    def success: Boolean
  }
  case class AppendEntriesResult(term: Int, success: Boolean) extends RPCResult
  case class RequestVoteResult(term: Int, success: Boolean) extends RPCResult

  
  
  case class Entry(term: Int, command: Command)
  case class CallBack(request: RPCMessage, replies: Seq[(ActorRef, Try[RPCResult])])
}
