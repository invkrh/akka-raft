package me.invkrh.raft.deploy.remote

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

import me.invkrh.raft.deploy.raftSystemName
import me.invkrh.raft.util.Logging

trait RemoteProvider extends Logging {
  val systemName: String = raftSystemName
  var host: String
  var port: Int

  def address: String = s"$host:$port"

  def systemShutdownHook(): Unit = {
    logInfo("Shutting down remote system")
  }

  // System is heavy, create as needed
  // The problem of being a singleton due to actor name conflict in the same system
  implicit lazy val system: ActorSystem = {
    logInfo(s"Creating remote system under $address")
    val config = Map(
      "akka.actor.provider" -> "remote",
      "akka.remote.artery.enabled" -> "on",
      "akka.remote.artery.canonical.hostname" -> host,
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
