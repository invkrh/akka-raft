package me.invkrh.raft.deploy.cluster

import java.io.File
import java.nio.file.Files

import scala.collection.JavaConverters._
import scala.language.postfixOps

import me.invkrh.raft.deploy.{RemoteProvider, _}
import me.invkrh.raft.util.ServerAddress

object Admin {
  val parser = new scopt.OptionParser[AdminConfig]("raft-admin.sh") {
    help("help").text("Show usage")

    opt[File]('f', "member-file")
      .required()
      .valueName("<member file>")
      .text("Member file path")
      .action((x, c) => c.copy(configFile = x))

    note("\n")

    cmd("init")
      .action((_, c) => c.copy(action = "init"))
      .text("trigger leader election")

    note("\n")

    cmd("stop")
      .action((_, c) => c.copy(action = "stop"))
      .text("stop all raft server")
  }

  def loadServerPaths(memberFile: File): Seq[String] = {
    val path = memberFile.toPath
    if (!Files.exists(path)) {
      throw new RuntimeException(s"MemberFile does not exist: $path")
    }
    Files.readAllLines(memberFile.toPath).asScala.map { addr =>
      ServerAddress(addr)
      s"akka.tcp://$raftSystemName@$addr/user/$raftServerName"
    }
  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, AdminConfig()) match {
      case Some(config) =>
        val serverPaths = loadServerPaths(config.configFile)
        new RemoteProvider {
          override def sysName: String = "raft-init"
          if (config.action == "init") {
            system.actorOf(Initiator.props(serverPaths))
          } else if (config.action == "stop") {
            system.actorOf(Terminator.props(serverPaths))
          } else {
            throw new IllegalArgumentException(s"Unknown command: ${config.action}")
          }
        }
      case None => throw new IllegalArgumentException("Args can not be valid")
    }
  }
}
