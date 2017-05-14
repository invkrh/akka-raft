package me.invkrh.raft.deploy.server

import scala.collection.JavaConverters._

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.FlatSpecLike

object Settings {
  // All probe created in the test suite with the following settings will be considered as
  // an eligible server
  val settingMap = Map(
    "akka.actor.provider" -> "remote",
    "akka.remote.netty.tcp.hostname" -> "localhost",
    "akka.remote.netty.tcp.port" -> "5000"
  ).asJava
  val conf = ConfigFactory
    .parseMap(settingMap)
    .withFallback(ConfigFactory.load())
}

class ServerLauncherTest
    extends TestKit(ActorSystem(raftSystemName, Settings.conf))
    with FlatSpecLike {

  "ServerLauncher" should "throw exception if no args are given" in {
    intercept[IllegalArgumentException] {
      ServerLauncher.main(Array())
    }
  }

  it should "throw exception if the given arg are malformed" in {
    intercept[IllegalArgumentException] {
      ServerLauncher.main(Array("-b", "localhost:asdf"))
    }
  }

  it should "throw exception if some args are unknown" in {
    intercept[IllegalArgumentException] {
      ServerLauncher.main(Array("-b", "localhost:1234", "-p", "remote"))
    }
  }
}
