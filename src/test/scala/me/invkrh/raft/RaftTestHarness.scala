package me.invkrh.raft

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import me.invkrh.raft.deploy.RemoteProvider
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import scala.collection.JavaConverters._

object RaftTestHarness {
  def localSystem(name: String): ActorSystem = {
    val config = Map("akka.actor.provider" -> "local").asJava
    val conf = ConfigFactory.parseMap(config).withFallback(ConfigFactory.load())
    val sys = ActorSystem(name, conf)
    sys
  }
  def remoteSystem(name: String): ActorSystem = {
    new RemoteProvider {
      override val systemName: String = name
    }.getSystem()
  }
  def testSystem(name: String, withRemote: Boolean): ActorSystem = {
    if (withRemote) {
      remoteSystem(name)
    } else {
      localSystem(name)
    }
  }
}

abstract class RaftTestHarness(specName: String, withRemote: Boolean = false)
    extends TestKit(RaftTestHarness.testSystem(specName, withRemote))
    with ImplicitSender
    with WordSpecLike
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
