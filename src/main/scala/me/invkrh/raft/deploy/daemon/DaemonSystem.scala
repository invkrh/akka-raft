package me.invkrh.raft.deploy.daemon

import java.nio.file.{Files, Paths}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import com.typesafe.config.ConfigFactory
import me.invkrh.raft.deploy._
import me.invkrh.raft.server.ServerConf
import me.invkrh.raft.util.FileUtils

object DaemonSystem extends RemoteProvider {
  override val systemName: String = daemonSystemName
  def main(args: Array[String]): Unit = {
    require(args.length == 1)
    val configFilePath = Paths.get(args.head)
    if (!Files.exists(configFilePath)) {
      throw new RuntimeException(s"Raft conf file does not exist: $configFilePath")
    } else {
      val config = ConfigFactory.parseFile(configFilePath.toFile)
      val hostname = config.getString("coordinator.hostname")
      val port = config.getInt("coordinator.port")
      val path = s"akka://$coordinatorSystemName@$hostname:$port/user/$serverInitializerName"
      val serverConf = ServerConf(config.getConfig("server"))
      val system = getSystem()
      implicit val executor: ExecutionContextExecutor = system.dispatcher
      system
        .actorSelection(path)
        .resolveOne(5.seconds)
        .onComplete {
          case Success(ref) =>
            // scalastyle:off println
            println("Find coordinator, asking for server ID")
            // scalastyle:on println
            system.actorOf(ServerSpawner.props(ref, serverConf), serverSpawnerName)
          case Failure(e) => throw e
        }
    }
  }
}
