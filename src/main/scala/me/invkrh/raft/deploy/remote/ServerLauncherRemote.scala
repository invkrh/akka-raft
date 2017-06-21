package me.invkrh.raft.deploy.remote

import scala.concurrent.duration._

import me.invkrh.raft.deploy.{clusterInitiatorName, serverLauncherName}
import me.invkrh.raft.deploy.actor.ServerLauncher
import me.invkrh.raft.exception.InvalidArgumentsException
import me.invkrh.raft.server.ServerConf
import me.invkrh.raft.util.{ActorRefUtils, CanonicalAddress, ConfigHolder, InetUtils, RaftConfig}

trait ServerLauncherRemote extends RemoteProvider { this: ConfigHolder =>

  private val localIP = InetUtils.findLocalInetAddress()

  var host: String = localIP
  var port: Int = 4545
  var initAddr: CanonicalAddress = CanonicalAddress(s"$localIP:9090") // No sys env variable

  if (System.getenv("RAFT_LAUNCHER_HOST") != null) {
    host = System.getenv("RAFT_LAUNCHER_HOST")
  }

  if (System.getenv("RAFT_LAUNCHER_PORT") != null) {
    port = System.getenv("RAFT_LAUNCHER_PORT").toInt
  }

  def parse(args: List[String]): Unit = args match {
    case ("--host" | "-h") :: value :: tail =>
      host = value
      parse(tail)
    case ("--port" | "-p") :: value :: tail =>
      port = value.toInt
      parse(tail)
    case ("--init" | "-i") :: value :: tail =>
      initAddr = CanonicalAddress(value)
      parse(tail)
    case Nil =>
    case _ => throw InvalidArgumentsException(args.mkString(" "))
  }

  def main(args: Array[String]): Unit = {
    parse(args.toList)
    implicit val resolutionTimeout = config.getInt("cluster.address.resolution.timeout.ms").millis
    val serverConf = ServerConf(config.getConfig("server"))
    val initRef = ActorRefUtils.resolveRefByName(systemName, initAddr, clusterInitiatorName)
    system.actorOf(ServerLauncher.props(initRef, serverConf), serverLauncherName)
  }
}

object ServerLauncherSystem extends ServerLauncherRemote with RaftConfig
