package me.invkrh.raft.message

import akka.actor.ActorRef

import me.invkrh.raft.core.ServerState
import me.invkrh.raft.message.ClientMessage.{Init, LogEntry}

object AdminMessage {
  case class Membership(memberDict: Map[Int, ActorRef]) extends RaftMessage
  case object MembershipRequest extends RaftMessage

  case object GetStatus extends RaftMessage
  case class Status(
    serverID: Int,
    term: Int,
    state: ServerState.Value,
    leader: Option[Int],
    logs: List[LogEntry] = List(LogEntry(0, Init, null))
  ) extends RaftMessage
}
