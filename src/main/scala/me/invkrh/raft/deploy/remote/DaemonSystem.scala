package me.invkrh.raft.deploy.remote

import scala.concurrent.{Await, TimeoutException}
import scala.concurrent.duration._
import scala.util.{Success, Try}

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.Config

import me.invkrh.raft.deploy._
import me.invkrh.raft.deploy.bootstrap.{ServerInitializer, ServerSpawner}
import me.invkrh.raft.message.{Membership, MembershipRequest, ShutDown, ShutDownACK}
import me.invkrh.raft.server.ServerConf

class DaemonSystem(config: Config, isPrecursorSystem: Boolean = false) extends RemoteProvider {
  private val resolutionTimeout = config.getInt("cluster.address.resolution.timeout.ms").millis
  private val precursorAddress = config.getString("cluster.precursor")

  private val serverConf = ServerConf(config.getConfig("server"))
  private implicit val timeout = Timeout(resolutionTimeout)
  private implicit val system =
    if (isPrecursorSystem) {
      createPrecursorSystem(config)
    } else {
      createSystem()
    }
  private implicit val executionContext = system.dispatcher

  val psr = new PrecursorSystemResolver(precursorAddress, resolutionTimeout)

  def init(): Unit = {
    val initialSize = config.getInt("cluster.quorum") * 2 - 1
    val initializer =
      system.actorOf(ServerInitializer.props(initialSize), serverInitializerName)
    // Create the precursor server
    system.actorOf(ServerSpawner.props(initializer, serverConf), serverSpawnerName)
  }

  def join(): Unit = {
    logInfo("Resolve initializer under coordinator, asking for server ID")
    system.actorOf(ServerSpawner.props(psr.initializer, serverConf), serverSpawnerName)
  }

  // TODO: membership changes / kill pid
  def stop(): Unit = {
    stopServerByChoice(getMembership)
    system.terminate()
  }

  def stopAll(): Unit = {
    stopServers(getMembership.values)
    system.terminate()
  }

  private def getMembership: Map[Int, ActorRef] = {
    Await.result(
      (psr.server ? MembershipRequest).mapTo[Membership].map(_.memberDict),
      resolutionTimeout
    )
  }

  private def shutdownServerWithACK(serverRef: ActorRef) = {
    // scalastyle:off
    val address = serverRef.path.address.toString
    logInfo(s"Shutting down server: $address")
    try {
      val serverId = Await.result((serverRef ? ShutDown).mapTo[ShutDownACK], resolutionTimeout).id
      println(s"[stopped] Server $serverId at $address")
    } catch {
      case e: TimeoutException =>
        println(s"Shutdown ack is not received from server [$address]")
    }
    // scalastyle:on
  }

  private def stopServers(servers: Iterable[ActorRef]): Unit = {
    servers foreach shutdownServerWithACK
  }

  private def stopServerByChoice(members: Map[Int, ActorRef]): Unit = {
    // scalastyle:off
    println("Choose servers your want to stop:")
    (members - 0).foreach {
      case (id, server) =>
        println(s"[$id] ${server.path.address}")
    }
    println(s"[*] All server including the precursor")

    val ans = scala.io.StdIn.readLine()
    if (ans == "*") {
      stopServers(members.values)
    } else {
      Try(ans.toInt) match {
        case Success(id) if id >= 1 && id < members.size => shutdownServerWithACK(members(id))
        case _ => println("Choice can not be recognized")
      }
    }
    // scalastyle:on
  }
}
