package me.invkrh.raft.deploy

import java.io.File

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import me.invkrh.raft.deploy.coordinator.CoordinatorSystem
import me.invkrh.raft.deploy.daemon.DaemonSystem
import me.invkrh.raft.message.{GetStatus, Status}
import me.invkrh.raft.RaftTestHarness

class IntegrationTest extends RaftTestHarness("IntegrationSpec", true) {

  /**
   * This test will use remote system define in the main function
   */
  "Cluster" should {
    "be launched correctly" in {
      val raftConfigFilePath = getClass.getResource(s"/raft.conf").getPath
      val config = ConfigFactory.parseFile(new File(raftConfigFilePath))
      CoordinatorSystem.main(Array(raftConfigFilePath))
      (1 to 3) foreach { _ =>
        DaemonSystem.main(Array(raftConfigFilePath))
      }
      Thread.sleep(5000)
      val refFuture = system
        .actorSelection(serverAddress(config))
        .resolveOne(10.seconds)

      import akka.pattern.ask
      implicit val timeout = Timeout(5.seconds)
      val leadFuture = for {
        ref <- refFuture
        Status(_, _, _, lead) <- ref ? GetStatus
      } yield {
        lead
      }

      assertResult(true) {
        Await.result(leadFuture, 5.seconds).nonEmpty
      }
    }
  }
}
