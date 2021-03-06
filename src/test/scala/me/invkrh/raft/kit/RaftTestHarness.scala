package me.invkrh.raft.kit

import java.io.File

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContextExecutor

import akka.actor.{Actor, ActorSystem, Scheduler}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

import me.invkrh.raft.deploy.remote.RemoteProvider
import me.invkrh.raft.message.ClientMessage.{Command, Init, LogEntry}
import me.invkrh.raft.util.{Logging, NetworkUtils}

trait TestHarness extends WordSpecLike with BeforeAndAfterAll with Logging {
  val configFilePath: String = getClass.getResource("/raft.conf").getPath
  val config: Config = ConfigFactory.parseFile(new File(configFilePath))
}

abstract class RaftTestHarness(specName: String, isRemote: Boolean = false)
  extends TestKit(RaftTestHarness.getSystem(specName, isRemote))
  with ImplicitSender
  with TestHarness {

  implicit val executor: ExecutionContextExecutor = system.dispatcher
  implicit val scheduler: Scheduler = system.scheduler

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def dummyEntry(term: Int, cmd: Command): LogEntry = {
    LogEntry(term, cmd, Actor.noSender)
  }

  def genDummyLogsUntilNewTerm(term: Int, newTermIndex: Int): List[LogEntry] = {
    List.tabulate(newTermIndex + 1) { i =>
      if (i != newTermIndex) {
        dummyEntry(term - 1, Init)
      } else {
        dummyEntry(term, Init)
      }
    }
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
