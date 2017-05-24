package me.invkrh.raft.deploy.daemon

import akka.actor.{Actor, ActorRef, Props}

import me.invkrh.raft.deploy._
import me.invkrh.raft.server.{Server, ServerConf}
import me.invkrh.raft.util.Logging

object ServerSpawner {
  def props(coordinatorRef: ActorRef, serverConf: ServerConf): Props =
    Props(new ServerSpawner(coordinatorRef, serverConf))
}

class ServerSpawner(coordinatorRef: ActorRef, serverConf: ServerConf) extends Actor with Logging {
  coordinatorRef ! AskServerID
  override def receive: Receive = {
    case ServerID(id) =>
      if (id < 0) {
        context.system.terminate()
      } else if (sender != coordinatorRef) {
        throw new RuntimeException("Unknown coordinator server")
      } else {
        val server = context.system.actorOf(Server.props(id, serverConf), s"$raftServerName-$id")
        logInfo(s"Server $id has been created at ${server.path}, wait for initialization")
        coordinatorRef ! Ready(id, server)
      }
  }
}
