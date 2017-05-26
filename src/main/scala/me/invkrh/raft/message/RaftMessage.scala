package me.invkrh.raft.message

import scala.util.Try

import akka.actor.ActorRef


trait RaftMessage

case class CallBack(request: RPCMessage, responses: Seq[(ActorRef, Try[RPCResult])])
  extends RaftMessage