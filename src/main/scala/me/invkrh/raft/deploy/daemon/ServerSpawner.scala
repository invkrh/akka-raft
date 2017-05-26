package me.invkrh.raft.deploy.daemon

import akka.actor.{Actor, ActorRef, Props}

import me.invkrh.raft.deploy._
import me.invkrh.raft.message.{AskServerID, Ready, ServerID}
import me.invkrh.raft.server.{Server, ServerConf}
import me.invkrh.raft.util.Logging

object ServerSpawner {
  def props(initializerRef: ActorRef, serverConf: ServerConf): Props =
    Props(new ServerSpawner(initializerRef, serverConf))
}

class ServerSpawner(initializerRef: ActorRef, serverConf: ServerConf) extends Actor with Logging {
  initializerRef ! AskServerID
  override def receive: Receive = {
    case ServerID(id) =>
      if (id < 0) {
        context.system.terminate()
      } else if (sender != initializerRef) {
        throw new RuntimeException("Unknown coordinator server")
      } else {
        val server = context.system.actorOf(Server.props(id, serverConf), s"$raftServerName-$id")
        logInfo(s"Server $id has been created at ${server.path}, wait for initialization")
        initializerRef ! Ready(id, server)
      }
  }
}
