package me.invkrh.raft.message

import akka.actor.ActorRef

import me.invkrh.raft.core.ServerState

object AdminMessage {
  case class Membership(memberDict: Map[Int, ActorRef]) extends RaftMessage

  case object GetStatus extends RaftMessage
  case class Status(
    serverID: Int,
    term: Int,
    state: ServerState.Value,
    leader: Option[Int],
    nextIndex: Map[Int, Int],
    matchIndex: Map[Int, Int],
    commitIndex: Int,
    lastApplied: Int
  ) extends RaftMessage
}
