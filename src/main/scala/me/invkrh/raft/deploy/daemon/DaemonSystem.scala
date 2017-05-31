package me.invkrh.raft.deploy.daemon

import java.nio.file.{Files, Paths}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import com.typesafe.config.ConfigFactory

import me.invkrh.raft.deploy._
import me.invkrh.raft.exception.RaftConfigurationFileNotFoundException
import me.invkrh.raft.server.ServerConf

object DaemonSystem extends RemoteProvider {
  override val systemName: String = daemonSystemName
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    val configFilePath = Paths.get(args.head)
    if (!Files.exists(configFilePath)) {
      throw RaftConfigurationFileNotFoundException(configFilePath)
    } else {
      val config = ConfigFactory.parseFile(configFilePath.toFile)
      val path = initializerAddress(config)
      val serverConf = ServerConf(config.getConfig("server"))
      val system = createSystem()
      implicit val executor: ExecutionContextExecutor = system.dispatcher
      system
        .actorSelection(path)
        .resolveOne(5.seconds)
        .onComplete {
          case Success(ref) =>
            // scalastyle:off println
            println("Resolve initializer under coordinator, asking for server ID")
            // scalastyle:on println
            system.actorOf(ServerSpawner.props(ref, serverConf), serverSpawnerName)
          case Failure(e) => throw e
        }
    }
  }
}
