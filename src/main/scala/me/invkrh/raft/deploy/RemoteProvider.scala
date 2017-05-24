package me.invkrh.raft.deploy

import java.net.InetAddress

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

import me.invkrh.raft.util.Logging

trait RemoteProvider extends Logging {
  val systemName: String = "RemoteSystem"

  def systemShutdownHook(): Unit = {
    logInfo(s"System [$systemName] has been shut down")
  }

  // System is heavy, create as needed
  // The problem of being a singleton due to actor name conflict in the same system
  def getSystem(hostName: String = InetAddress.getLocalHost.getCanonicalHostName,
                port: Int = 0): ActorSystem = {
    val config = Map(
      "akka.actor.provider" -> "remote",
      "akka.remote.artery.enabled" -> "on",
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
