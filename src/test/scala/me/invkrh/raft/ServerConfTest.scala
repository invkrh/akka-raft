package me.invkrh.raft

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.ConfigFactory
import me.invkrh.raft.core.ServerConf
import org.scalatest.FlatSpecLike

class ServerConfTest extends FlatSpecLike {
  "ServerConf" should "be built from configure file" in {
    val confStr =
      """
        |serverId=1
        |minElectionTime=150
        |maxElectionTime=150
        |tickTime=100
        |server.1=svr1.invkrh.me
        |server.2=svr2.invkrh.me
        |server.3=svr3.invkrh.me
      """.stripMargin
    val config = ConfigFactory.parseString(confStr)
    assertResult(
      ServerConf(1,
        150 millis,
        150 millis,
        100 millis,
        Map(1 -> "svr1.invkrh.me", 2 -> "svr2.invkrh.me", 3 -> "svr3.invkrh.me"))) {
      ServerConf(config)
    }
  }
}
