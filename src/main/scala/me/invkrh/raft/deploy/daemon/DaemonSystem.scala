package me.invkrh.raft.deploy.daemon

import java.nio.file.{Files, Paths}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import me.invkrh.raft.deploy._
import me.invkrh.raft.server.ServerConf
import me.invkrh.raft.util.FileUtils

object DaemonSystem extends RemoteProvider {
  def main(args: Array[String]): Unit = {
    require(args.length == 2)
    val bootstrapLocationFilePath = Paths.get(args(0))
    val serverConfigFilePath = Paths.get(args(1))
    if (!Files.exists(bootstrapLocationFilePath)) {
      throw new RuntimeException(s"File does not exist: $bootstrapLocationFilePath")
    } else if (!Files.exists(serverConfigFilePath)) {
      throw new RuntimeException(s"File does not exist: $serverConfigFilePath")
    } else {
      val address = FileUtils.getCoordinatorSystemAddress(bootstrapLocationFilePath)
      val conf = ServerConf(serverConfigFilePath.toFile)
      val path = s"akka://$bootstrapSystemName@$address/user/$serverInitializerName"
      val system = createSystem()
      implicit val executor: ExecutionContextExecutor = system.dispatcher
      system
        .actorSelection(path)
        .resolveOne(5.seconds)
        .onComplete {
          case Success(ref) =>
            // scalastyle:off println
            println("Find bootstrap coordinator, asking for server ID")
            // scalastyle:on println
            system.actorOf(ServerSpawner.props(ref, conf), serverSpawnerName)
          case Failure(e) => throw e
        }
    }
  }
}
