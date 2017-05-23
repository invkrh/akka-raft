package me.invkrh.raft.deploy.bootstrap

import java.nio.file.{Files, Paths}

import me.invkrh.raft.deploy._
import me.invkrh.raft.util.FileUtils

object BootstrapSystem extends RemoteProvider {
  def main(args: Array[String]): Unit = {
    require(args.length == 2)
    val bootstrapLocationFilePath = Paths.get(args(0))
    val initialSize = args(1).toInt
    if (!Files.exists(bootstrapLocationFilePath)) {
      throw new RuntimeException(s"File does not exist: $bootstrapLocationFilePath")
    } else if (initialSize <= 1) {
      throw new RuntimeException("More than one severs should be initialized")
    } else {
      val loc = FileUtils.getCoordinatorSystemAddress(bootstrapLocationFilePath)
      val system = createSystem(loc.hostName, loc.port, bootstrapSystemName)
      system.actorOf(ServerInitializer.props(initialSize), serverInitializerName)
    }
  }
}
