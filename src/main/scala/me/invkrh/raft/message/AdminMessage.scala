package me.invkrh.raft.message

import akka.actor.ActorRef

import me.invkrh.raft.core.ServerState

sealed trait AdminMessage extends RaftMessage
case class Membership(memberDict: Map[Int, ActorRef]) extends AdminMessage
case object MembershipRequest extends AdminMessage

case object GetStatus extends AdminMessage
case class Status(serverID: Int, term: Int, state: ServerState.Value, leader: Option[Int])
    extends AdminMessage
