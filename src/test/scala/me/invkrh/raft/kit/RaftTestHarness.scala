package me.invkrh.raft.kit

import java.io.File

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContextExecutor

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import me.invkrh.raft.deploy.remote.RemoteProvider

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
    }.createSystem()
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

  val config: Config =
    ConfigFactory.parseFile(new File(getClass.getResource(s"/raft.conf").getPath))

  implicit val executor: ExecutionContextExecutor = system.dispatcher
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
