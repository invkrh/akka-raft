package me.invkrh.raft.server

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.Config

case class ServerConf(minElectionTime: FiniteDuration,
                      maxElectionTime: FiniteDuration,
                      tickTime: FiniteDuration)

object ServerConf {
  def apply(config: Config): ServerConf = {
    val minElectionTime = config.getInt("election.timeout.min.ms").millis
    val maxElectionTime = config.getInt("election.timeout.max.ms").millis
    val tickTime = config.getInt("heartbeat.interval.ms").millis
    ServerConf(minElectionTime, maxElectionTime, tickTime)
  }
}
