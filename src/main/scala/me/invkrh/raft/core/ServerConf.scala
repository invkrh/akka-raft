package me.invkrh.raft.core

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.Config

import me.invkrh.raft.storage.DataStore

case class ServerConf(
    minElectionTime: FiniteDuration,
    maxElectionTime: FiniteDuration,
    tickTime: FiniteDuration,
    rpcRetries: Int,
    dataStore: DataStore)

object ServerConf {
  def apply(config: Config): ServerConf = {
    val minElectionTime = config.getInt("election.timeout.min.ms").millis
    val maxElectionTime = config.getInt("election.timeout.max.ms").millis
    val tickTime = config.getInt("heartbeat.interval.ms").millis
    val rpcRetries = config.getInt("rpc.retries")
    val dataStore = DataStore(config.getConfig("datastore"))
    ServerConf(minElectionTime, maxElectionTime, tickTime, rpcRetries, dataStore)
  }
}
