package me.invkrh.raft.deploy.server

import java.io.File
import java.nio.file.Files

import scala.collection.JavaConverters._
import scala.language.postfixOps

import me.invkrh.raft.deploy.SystemProvider
import me.invkrh.raft.util.ServerAddress

class RaftInit(members: Seq[String]) extends SystemProvider {
  override def sysPort: Int = 6000
  override def sysHostName: String = "localhost"
  override def sysName: String = "raft-init"

  def launch(): Unit = {
    system.actorOf(InitialStatusChecker.props(members))
  }
}

object RaftInit {
  val parser = new scopt.OptionParser[InitConfig]("raft-init.sh") {
    opt[File]('f', "member-file")
      .required()
      .valueName("<member file>")
      .text("Member file path")
      .action((x, c) => c.copy(memberFile = x))
      .validate { file =>
        val path = file.toPath
        if (Files.exists(path)) {
          success
        } else {
          failure(s"File: $file does not exist")
        }
      }
  }

  def loadServerPaths(memberFile: File): Seq[String] = {
    Files.readAllLines(memberFile.toPath).asScala.map { addr =>
      ServerAddress(addr)
      s"akka.tcp://$raftSystemName@$addr/user/$raftServerName"
    }
  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, InitConfig()) match {
      case Some(config) =>
        val serverPaths = loadServerPaths(config.memberFile)
        new RaftInit(serverPaths).launch()
      case None => throw new IllegalArgumentException("Args can not be valid")
    }
  }
}
