package me.invkrh.raft.deploy.remote

import me.invkrh.raft.deploy.actor.ClusterInitiator
import me.invkrh.raft.deploy.clusterInitiatorName
import me.invkrh.raft.exception.InvalidArgumentsException
import me.invkrh.raft.util.{ConfigHolder, InetUtils, RaftConfig}

trait ClusterInitiatorRemote extends RemoteProvider { this: ConfigHolder =>
  var host: String = InetUtils.findLocalInetAddress()
  var port: Int = 9090

  if (System.getenv("RAFT_INITIATOR_HOST") != null) {
    host = System.getenv("RAFT_INITIATOR_HOST")
  }

  if (System.getenv("RAFT_INITIATOR_PORT") != null) {
    port = System.getenv("RAFT_INITIATOR_PORT").toInt
  }

  def parse(args: List[String]): Unit = args match {
    case ("--host" | "-h") :: value :: tail =>
      host = value
      parse(tail)
    case ("--port" | "-p") :: value :: tail =>
      port = value.toInt
      parse(tail)
    case Nil =>
    case _ => throw InvalidArgumentsException(args.mkString(" "))
  }

  def main(args: Array[String]): Unit = {
    parse(args.toList)
    val initialSize = config.getInt("cluster.quorum") * 2 - 1
    system.actorOf(ClusterInitiator.props(initialSize), clusterInitiatorName)
    // scalastyle:off
    println(s"Initiator url: $host:$port")
    // scalastyle:on
  }
}

object ClusterInitiatorSystem extends ClusterInitiatorRemote with RaftConfig
