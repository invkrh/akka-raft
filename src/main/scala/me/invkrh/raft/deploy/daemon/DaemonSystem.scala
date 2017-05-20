package me.invkrh.raft.deploy.daemon

import java.nio.file.{Files, Paths}

import me.invkrh.raft.deploy._
import me.invkrh.raft.server.ServerConf
import me.invkrh.raft.util.FileUtils

object DaemonSystem {
  def main(args: Array[String]): Unit = {
    require(args.length == 2)
    val bootstrapLocationFilePath = Paths.get(args(0))
    val serverConfigFilePath = Paths.get(args(1))
    if (!Files.exists(bootstrapLocationFilePath)) {
      throw new RuntimeException(s"File does not exist: $bootstrapLocationFilePath")
    } else if (!Files.exists(serverConfigFilePath)) {
      throw new RuntimeException(s"File does not exist: $serverConfigFilePath")
    } else {
      val loc = FileUtils.getCoordinatorSystemAddress(bootstrapLocationFilePath)
      val conf = ServerConf(serverConfigFilePath.toFile)
      new RemoteProvider {
        override def sysName: String = daemonSystemName
        system.actorOf(ServerSpawner.props(loc, conf), serverSpawnerName)
      }
    }
  }
}
