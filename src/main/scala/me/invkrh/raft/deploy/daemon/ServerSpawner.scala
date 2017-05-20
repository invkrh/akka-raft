package me.invkrh.raft.deploy.daemon

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

import akka.actor.{Actor, Props}
import me.invkrh.raft.deploy._
import me.invkrh.raft.server.{Server, ServerConf}
import me.invkrh.raft.util.{Location, Logging}

object ServerSpawner {
  def props(coordinatorAddress: Location, serverConf: ServerConf) =
    Props(new ServerSpawner(coordinatorAddress, serverConf))
}

class ServerSpawner(bootstrapAddress: Location, serverConf: ServerConf)
    extends Actor
    with Logging {
  implicit val executor: ExecutionContextExecutor = context.system.dispatcher
  context.system
    .actorSelection(
      s"akka.tcp://$bootstrapSystemName@$bootstrapAddress/user/$serverInitializerName"
    )
    .resolveOne(5.seconds)
    .foreach { ref =>
      logInfo("Find bootstrap coordinator, asking for server ID")
      ref ! AskServerID
    }

  override def receive: Receive = {
    case ServerID(id) =>
      val server = context.actorOf(Server.props(id, serverConf), raftServerName)
      logInfo(s"Server $id has been created at ${server.path}, wait for initialization")
      sender ! Ready(server)
  }
}
