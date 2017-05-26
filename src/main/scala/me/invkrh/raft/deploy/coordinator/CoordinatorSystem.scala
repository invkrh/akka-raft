package me.invkrh.raft.deploy.coordinator

import java.nio.file.{Files, Paths}

import com.typesafe.config.ConfigFactory
import me.invkrh.raft.deploy._
import me.invkrh.raft.deploy.daemon.ServerSpawner
import me.invkrh.raft.server.ServerConf

object CoordinatorSystem extends RemoteProvider {
  override val systemName: String = coordinatorSystemName
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    val configFilePath = Paths.get(args.head)
    if (!Files.exists(configFilePath)) {
      throw new RuntimeException(s"Raft conf file does not exist: $configFilePath")
    } else {
      val config = ConfigFactory.parseFile(configFilePath.toFile)
      val system =
        getSystem(config.getString("coordinator.hostname"), config.getInt("coordinator.port"))
      val initialSize = config.getInt("coordinator.quorum") * 2 - 1
      val serverConf = ServerConf(config.getConfig("server"))
      val initRef =
        system.actorOf(ServerInitializer.props(initialSize, serverConf), serverInitializerName)
      system.actorOf(ServerSpawner.props(initRef, serverConf), serverSpawnerName)
    }
  }
}
