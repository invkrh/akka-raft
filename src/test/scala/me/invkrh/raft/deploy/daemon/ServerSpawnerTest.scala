package me.invkrh.raft.deploy.daemon

import java.io.File

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import me.invkrh.raft.kit.ExceptionDetector
import me.invkrh.raft.message.{ServerId, ServerIdRequest}
import me.invkrh.raft.server.ServerConf
import me.invkrh.raft.RaftTestHarness

class ServerSpawnerTest extends RaftTestHarness("ServerSpawnerTest") {
  "ServerSpawner" should {
    val raftConfigFilePath = getClass.getResource(s"/raft.conf").getPath
    val config = ConfigFactory.parseFile(new File(raftConfigFilePath))
    val serverConf = ServerConf(config.getConfig("server"))

    "shutdown the system when ServerIdRequest is rejected" in {
      val spawnerRef = system.actorOf(ServerSpawner.props(self, serverConf))
      expectMsg(ServerIdRequest)
      spawnerRef ! ServerId(-1)
      Thread.sleep(1000)
      assertResult(true) {
        system.whenTerminated.isCompleted
      }
    }

    "throw RuntimeException if ServerId is not sent from initializer" in {
      val probe = TestProbe()
      val supervisor: ActorRef =
        system.actorOf(Props(new ExceptionDetector(s"spawner", probe.ref)))
      supervisor ! ServerSpawner.props(probe.ref, serverConf)
      val spawnerRef = expectMsgType[ActorRef]
      probe.expectMsg(ServerIdRequest)
      spawnerRef ! ServerId(0)
      probe.expectMsgType[RuntimeException]
    }
  }
}
