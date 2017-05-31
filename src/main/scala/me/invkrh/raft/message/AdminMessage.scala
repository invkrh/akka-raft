package me.invkrh.raft.message

import akka.actor.ActorRef

import me.invkrh.raft.server.ServerState

sealed trait AdminMessage extends RaftMessage
case class Init(memberDict: Map[Int, ActorRef]) extends AdminMessage
case object GetStatus extends AdminMessage
case object ShutDown extends AdminMessage
case class Status(serverID: Int, term: Int, state: ServerState.Value, leader: Option[Int])
    extends AdminMessage
