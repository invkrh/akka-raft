package me.invkrh.raft.server

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory
import org.scalatest.FlatSpecLike

class ServerConfTest extends FlatSpecLike {
  "ServerConf" should "be built from configure file" in {
    val confStr =
      """
        |id=1
        |election.timeout.min.ms=150
        |election.timeout.max.ms=150
        |heartbeat.interval.ms=100
        |listener="localhost:5000"
      """.stripMargin
    val config = ConfigFactory.parseString(confStr)
    assertResult(ServerConf(1, 150 millis, 150 millis, 100 millis, "localhost", 5000)) {
      ServerConf(config)
    }
  }

  it should "throw exception if listener is malformed" in {
    val confStr =
      """
        |id=1
        |election.timeout.min.ms=150
        |election.timeout.max.ms=150
        |heartbeat.interval.ms=100
        |listener="localhost:abc"
      """.stripMargin
    val config = ConfigFactory.parseString(confStr)
    intercept[IllegalArgumentException] {
      ServerConf(config)
    }
  }
}
