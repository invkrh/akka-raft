package me.invkrh.raft.kit

import java.io.File

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContextExecutor

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import me.invkrh.raft.deploy.remote.RemoteProvider
import me.invkrh.raft.util.{Logging, NetworkUtils}

trait TestHarness extends WordSpecLike with BeforeAndAfterAll with Logging {
  val configFilePath: String = getClass.getResource("/raft.conf").getPath
  implicit val config: Config =
    ConfigFactory.parseFile(new File(configFilePath))
}

abstract class RaftTestHarness(specName: String, isRemote: Boolean = false)
    extends TestKit(RaftTestHarness.getSystem(specName, isRemote))
    with ImplicitSender
    with TestHarness {

  implicit val executor: ExecutionContextExecutor = system.dispatcher
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}

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
      override var host: String = NetworkUtils.findLocalInetAddress()
      override var port: Int = 0
    }.system
  }

  def getSystem(name: String, withRemote: Boolean): ActorSystem = {
    if (withRemote) {
      remoteSystem(name)
    } else {
      localSystem(name)
    }
  }
}
