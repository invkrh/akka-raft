package me.invkrh.raft.deploy

import java.net.InetAddress

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import me.invkrh.raft.util.Logging

trait RemoteProvider extends Logging {
  def systemShutdownHook(): Unit = {
    logInfo("System has been shut down")
  }

  // System is heavy, create as needed
  def createSystem(hostName: String = InetAddress.getLocalHost.getCanonicalHostName,
                   port: Int = 0,
                   systemName: String = "RemoteSystem"): ActorSystem = {
    val config = Map(
      "akka.remote.artery.canonical.hostname" -> hostName,
      "akka.remote.artery.canonical.port" -> port.toString
    ).asJava
    val conf = ConfigFactory.parseMap(config).withFallback(ConfigFactory.load())
    val sys = ActorSystem(systemName, conf)
    sys.whenTerminated foreach { _ =>
      systemShutdownHook()
    }
    sys
  }
}
