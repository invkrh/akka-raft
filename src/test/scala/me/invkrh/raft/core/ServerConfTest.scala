package me.invkrh.raft.core

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory
import org.scalatest.FlatSpecLike

class ServerConfTest extends FlatSpecLike {
  "ServerConf" should "be built from configure file" in {
    val confStr =
      """
        |election.timeout.min.ms=150
        |election.timeout.max.ms=150
        |heartbeat.interval.ms=100
      """.stripMargin
    val config = ConfigFactory.parseString(confStr)
    assertResult(ServerConf(150 millis, 150 millis, 100 millis)) {
      ServerConf(config)
    }
  }
}
