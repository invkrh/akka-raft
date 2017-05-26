package me.invkrh.raft.message

import akka.actor.ActorRef

import me.invkrh.raft.server.State

sealed trait AdminMessage extends RaftMessage
case class Init(memberDict: Map[Int, ActorRef]) extends AdminMessage
case object GetStatus extends AdminMessage
case object Shutdown extends AdminMessage
case class Status(serverID: Int, term: Int, state: State.Value, leader: Option[Int])
  extends AdminMessage
