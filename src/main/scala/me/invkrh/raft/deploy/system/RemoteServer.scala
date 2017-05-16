package me.invkrh.raft.deploy.system

import java.io.File
import java.nio.file.Files

import akka.actor.ActorRef
import me.invkrh.raft.deploy.RemoteProvider
import me.invkrh.raft.server.{Server, ServerConf}
import me.invkrh.raft.deploy._

object RemoteServer {

  val parser = new scopt.OptionParser[AdminConfig]("server-admin.sh") {
    help("help").text("Show usage")

    opt[File]('f', "config-file")
      .required()
      .valueName("<server.properties file>")
      .text("Server configuration file path")
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

  def main(args: Array[String]): Unit = {
    parser.parse(args, AdminConfig()) match {
      case Some(config) =>
        val path = config.configFile.toPath
        if (!Files.exists(path)) {
          throw new RuntimeException(s"Server properties file does not exist: $path")
        }
        val srvConf = ServerConf(config.configFile)
        new RemoteProvider {
          override def sysPort: Int = srvConf.port
          override def sysName: String = raftSystemName
          override def sysHostName: String = srvConf.host
          val server: ActorRef = system.actorOf(Server.props(srvConf), s"$raftServerName")
        }
      case None =>
        throw new IllegalArgumentException("Args can not be valid")
    }
  }
}
