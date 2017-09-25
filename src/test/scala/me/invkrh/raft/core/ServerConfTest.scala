package me.invkrh.raft.core

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory
import org.scalatest.FlatSpecLike

import me.invkrh.raft.storage.DataStore

class ServerConfTest extends FlatSpecLike {
  "ServerConf" should "be built from configure file" in {
    val confStr =
      """
        |election.timeout.min.ms = 150
        |election.timeout.max.ms = 150
        |heartbeat.interval.ms = 100
        |rpc.retries = 1
        |datastore.type = memory
      """.stripMargin
    val config = ConfigFactory.parseString(confStr)

    val result = ServerConf(config)
    val expected =
      ServerConf(150 millis, 150 millis, 100 millis, 1, DataStore(config.getConfig("datastore")))

    assertResult(expected) { result }
  }
}
