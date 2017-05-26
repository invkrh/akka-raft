package me.invkrh.raft.message

import akka.actor.ActorRef

sealed  trait DeployMessage extends RaftMessage
case object AskServerID extends DeployMessage
case class ServerID(id: Int) extends DeployMessage
case class Ready(id: Int, serverRef: ActorRef) extends DeployMessage
