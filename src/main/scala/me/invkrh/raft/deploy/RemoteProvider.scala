package me.invkrh.raft.deploy

import java.net.InetAddress

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import me.invkrh.raft.util.Logging

trait RemoteProvider extends Logging {
  def sysName: String
  def sysPort: Int = 0
  def sysHostName: String = InetAddress.getLocalHost.getCanonicalHostName

  def systemShutdownHook(): Unit = {
    logInfo("System has been shut down")
  }

  // System is heavy, created as needed
  lazy val system: ActorSystem = {
    val config = Map(
      "akka.remote.artery.canonical.hostname" -> sysHostName,
      "akka.remote.artery.canonical.port" -> sysPort.toString
    ).asJava
    val conf = ConfigFactory.parseMap(config).withFallback(ConfigFactory.load())
    val sys = ActorSystem(sysName, conf)
    sys.whenTerminated foreach { _ =>
      systemShutdownHook()
    }
    sys
  }
}
