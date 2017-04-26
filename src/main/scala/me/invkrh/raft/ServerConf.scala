package me.invkrh.raft

import java.io.File
import java.net.URL

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import com.typesafe.config.{Config, ConfigFactory}

case class ServerConf(id: Int,
                      minElectionTime: FiniteDuration,
                      maxElectionTime: FiniteDuration,
                      tickTime: FiniteDuration,
                      memberDict: Map[Int, String])

object ServerConf {

  def apply(configURL: URL): ServerConf = {
    val config = ConfigFactory.parseURL(configURL)
    apply(config)
  }

  def apply(configFile: File): ServerConf = {
    val config = ConfigFactory.parseFile(configFile)
    apply(config)
  }

  def apply(confStr: String): ServerConf = {
    val config = ConfigFactory.parseString(confStr)
    apply(config)
  }

  def apply(config: Config): ServerConf = {
    val id = config.getInt("serverId")
    val minElectionTime = config.getInt("minElectionTime") millis
    val maxElectionTime = config.getInt("maxElectionTime") millis
    val tickTime = config.getInt("tickTime") millis
    val memberDict = config
      .getConfig("server")
      .entrySet()
      .asScala
      .toList
      .map { entry =>
        entry.getKey.toInt -> entry.getValue.unwrapped().toString
      }
      .toMap
    ServerConf(id, minElectionTime, maxElectionTime, tickTime, memberDict)
  }
}
