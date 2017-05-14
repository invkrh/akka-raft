package me.invkrh.raft.deploy.server

import java.io.File
import java.nio.file.Files

import me.invkrh.raft.deploy.SystemProvider
import me.invkrh.raft.server.{Server, ServerConf}
import me.invkrh.raft.util.ServerAddress

class ServerStart(serverConf: ServerConf) extends SystemProvider {
  override def sysName: String = raftSystemName
  override def sysPort: Int = serverConf.port
  override def sysHostName: String = serverConf.host

  def spawn(): Unit = {
    val server = system.actorOf(Server.props(serverConf), s"$raftServerName")
    logInfo(s"Server is created at: ${server.path}, waiting for initialization")
  }
}

object ServerStart {
  val parser = new scopt.OptionParser[StartConfig]("server-start.sh") {
    opt[File]('f', "config-file")
      .required()
      .valueName("<server.properties file>")
      .text("Server configuration file path")
      .action((x, c) => c.copy(configFile = x))
      .validate { file =>
        if (Files.exists(file.toPath)) {
          success
        } else {
          failure(s"File: $file does not exist")
        }
      }
  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, StartConfig()) match {
      case Some(config) =>
        val srvConf = ServerConf(config.configFile)
        new ServerStart(srvConf).spawn()
      case None =>
        throw new IllegalArgumentException("Args can not be valid")
    }
  }
}
