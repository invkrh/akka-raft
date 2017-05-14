package me.invkrh.raft.deploy.server

import java.io.File

import me.invkrh.raft.deploy.SystemProvider
import me.invkrh.raft.server.{Server, ServerConf}

class ServerSpawner(serverConf: ServerConf) extends SystemProvider {
  override def sysName: String = raftSystemName
  override def sysPort: Int = serverConf.lsnPort
  override def sysHostName: String = serverConf.lsnHostName

  def spawn(): Unit = {
    val system = createSystem()
    val server = system.actorOf(Server.props(serverConf), s"$raftServerName")
    logInfo(s"Server is created at: ${server.path}")
  }
}

object ServerSpawner {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Only one argument is required: server.properties")
    val filePath = args.head
    val srvConf = ServerConf(new File(filePath))
    new ServerSpawner(srvConf).spawn()
  }
}
