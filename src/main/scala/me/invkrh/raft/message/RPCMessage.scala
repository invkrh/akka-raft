package me.invkrh.raft.message

import scala.util.Try

import akka.actor.ActorRef

sealed trait RPCMessage extends RaftMessage { def term: Int }

case class AppendEntries(term: Int,
                         leaderId: Int,
                         prevLogIndex: Int,
                         prevLogTerm: Int,
                         entries: Seq[LogEntry],
                         leaderCommit: Int)
    extends RPCMessage
case class RequestVote(term: Int, candidateId: Int, lastLogIndex: Int, lastLogTerm: Int)
    extends RPCMessage

// Internal RPC response message
trait RPCResult extends RPCMessage { def success: Boolean }
case class AppendEntriesResult(term: Int, success: Boolean) extends RPCResult
case class RequestVoteResult(term: Int, success: Boolean) extends RPCResult


