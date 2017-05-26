package me.invkrh.raft.deploy.daemon

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import me.invkrh.raft.deploy.RemoteProvider
import me.invkrh.raft.exception.UnknownInitializerException
import me.invkrh.raft.kit.ExceptionDetector
import me.invkrh.raft.message.{ServerId, ServerIdRequest}
import me.invkrh.raft.RaftTestHarness
import me.invkrh.raft.server.ServerConf

class ServerSpawnerTest extends RaftTestHarness("ServerSpawnerTest") {
  "ServerSpawner" should {
    val raftConfigFilePath = getClass.getResource(s"/raft.conf").getPath
    val config = ConfigFactory.parseFile(new File(raftConfigFilePath))
    val serverConf = ServerConf(config.getConfig("server"))

    "shutdown the system when ServerIdRequest is rejected" in {
      new RemoteProvider {
        val system: ActorSystem = getSystem()
        val spawnerRef: ActorRef = system.actorOf(ServerSpawner.props(self, serverConf))
        expectMsg(ServerIdRequest)
        spawnerRef ! ServerId(-1)
        Thread.sleep(1000)
        assertResult(true) {
          system.whenTerminated.isCompleted
        }
      }
    }

    "throw UnknownInitializerException if ServerId is not sent from initializer" in {
      val probe = TestProbe()
      val supervisor: ActorRef =
        system.actorOf(Props(new ExceptionDetector(s"spawner", probe.ref)))
      supervisor ! ServerSpawner.props(probe.ref, serverConf)
      val spawnerRef = expectMsgType[ActorRef]
      probe.expectMsg(ServerIdRequest)
      spawnerRef ! ServerId(0)
      probe.expectMsgType[UnknownInitializerException]
    }
  }
}
