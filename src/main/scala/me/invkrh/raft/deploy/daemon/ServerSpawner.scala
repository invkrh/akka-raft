package me.invkrh.raft.deploy.daemon

import akka.pattern.{ask, pipe}
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import me.invkrh.raft.deploy._
import me.invkrh.raft.server.{Server, ServerConf}
import me.invkrh.raft.util.{Location, Logging}

object ServerSpawner {
  def props(bootstrapRef: ActorRef, serverConf: ServerConf): Props =
    Props(new ServerSpawner(bootstrapRef, serverConf))
}

class ServerSpawner(bootstrapRef: ActorRef, serverConf: ServerConf) extends Actor with Logging {
  bootstrapRef ! AskServerID
  override def receive: Receive = {
    case ServerID(id) =>
      if (sender != bootstrapRef) {
        throw new RuntimeException("Unknown bootstrap server")
      } else {
        val server = context.actorOf(Server.props(id, serverConf), s"$raftServerName-$id")
        logInfo(s"Server $id has been created at ${server.path}, wait for initialization")
        bootstrapRef ! Ready(id, server)
      }
  }
}
