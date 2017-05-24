package me.invkrh.raft.server

import java.io.File
import java.net.URL
import java.util.Properties

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.{Config, ConfigFactory}

case class ServerConf(minElectionTime: FiniteDuration,
                      maxElectionTime: FiniteDuration,
                      tickTime: FiniteDuration)

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

  def apply(confProp: Properties): ServerConf = {
    val config = ConfigFactory.parseProperties(confProp)
    apply(config)
  }

  def apply(config: Config): ServerConf = {
    val minElectionTime = config.getInt("election.timeout.min.ms").millis
    val maxElectionTime = config.getInt("election.timeout.max.ms").millis
    val tickTime = config.getInt("heartbeat.interval.ms").millis
    ServerConf(minElectionTime, maxElectionTime, tickTime)
  }
}
