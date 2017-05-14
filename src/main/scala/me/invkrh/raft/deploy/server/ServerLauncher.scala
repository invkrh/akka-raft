package me.invkrh.raft.deploy.server

import java.net.{MalformedURLException, URI, URL}

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import me.invkrh.raft.deploy.SystemProvider
import me.invkrh.raft.server.Message.{GetStatus, Init, Status}
import me.invkrh.raft.server.State
import me.invkrh.raft.util.Logging

class ServerLauncher(config: LaunchConfig) extends SystemProvider {
  override def sysPort: Int = 6000
  override def sysHostName: String = "localhost"
  override def sysName: String = "raft-system-launcher"

  def launch(): Unit = {
    val system = createSystem()
    system.actorOf(InitialStatusChecker.props(config.bootstrapServers))
  }
}

case class LaunchConfig(bootstrapServers: Seq[String] = Seq())

object ServerLauncher {
  val parser = new scopt.OptionParser[LaunchConfig]("raft-server-start.sh") {
    private def addressValidation(addrs: Seq[String]) = {
      try {
        addrs.foreach { addr =>
          new URL("http://" + addr) // http is not used, just to fill protocol field
        }
        success
      } catch {
        case e: Exception => failure(s"[${e.getClass.toString}] ${e.getMessage}")
      }
    }
    opt[Seq[String]]('b', "bootstrap-server")
      .required()
      .valueName("<server1:port1>,<server2:port2>...")
      .action((x, c) => c.copy(bootstrapServers = x))
      .text("Initial servers in the cluster")
      .validate(addressValidation)
  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, LaunchConfig()) match {
      case Some(config) => new ServerLauncher(config).launch()
      case None => throw new IllegalArgumentException("Args can not be recognized ")
    }
  }

}

case object ResolutionTimeout

object InitialStatusChecker {
  def props(bootstrapServers: Seq[String]) = Props(new InitialStatusChecker(bootstrapServers))
}

class InitialStatusChecker(bootstrapServers: Seq[String]) extends Actor with Logging {
  import context.dispatcher
  var memberDict: Map[Int, ActorRef] = Map()

  logInfo("Resolving servers ...")
  for (addr <- bootstrapServers) {
    val loc = s"akka.tcp://$raftSystemName@$addr/user/$raftServerName"
    println(loc)
    val ft = context
      .actorSelection(loc)
      .resolveOne(5.seconds)
    ft pipeTo self
  }

  private val timeout = context.system.scheduler.scheduleOnce(10.seconds, self, ResolutionTimeout)

  override def receive: Receive = {
    case server: ActorRef =>
      println("server =>" + server.path)
      server ! GetStatus
    case Status(id, 0, State.Bootstrap, None) => // check initial states of all concerned servers
      memberDict.get(id) match {
        case Some(_) => throw new RuntimeException(s"Server $id already exists")
        case None =>
          memberDict = memberDict.updated(id, sender())
          logInfo(
            s"(${memberDict.size}/${bootstrapServers.size}) " +
              s"Found server $id at ${sender().path}"
          )
          if (memberDict.size == bootstrapServers.size) {
            timeout.cancel()
            logInfo("All the servers are found. Cluster is up. Wait for leader election...")
            memberDict foreach {
              case (_, ref) => ref ! Init(memberDict)
            }
            context.system.terminate()
          }
      }
    case ResolutionTimeout =>
      logError("Bootstrap server resolution failed. Shutting down the system...")
      context.system.terminate()
  }
}
