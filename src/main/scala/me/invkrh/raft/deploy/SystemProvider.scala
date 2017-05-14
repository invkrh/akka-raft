package me.invkrh.raft.deploy

import scala.collection.JavaConverters._

import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import me.invkrh.raft.util.Logging

trait SystemProvider extends Logging {
  def sysName: String
  def sysPort: Int
  def sysHostName: String = ""

  def systemShutdownHook(): Unit = {
    logInfo("System has been shut down")
  }

  // System is heavy, created as needed
  def createSystem(): ActorSystem = {
    val config = Map(
      "akka.actor.provider" -> "remote",
      "akka.remote.netty.tcp.hostname" -> sysHostName,
      "akka.remote.netty.tcp.port" -> sysPort.toString
    ).asJava
    val conf = ConfigFactory.parseMap(config).withFallback(ConfigFactory.load())
    val sys = ActorSystem(sysName, conf)
    sys.whenTerminated foreach { _ =>
      systemShutdownHook()
    }
    sys
  }

}
