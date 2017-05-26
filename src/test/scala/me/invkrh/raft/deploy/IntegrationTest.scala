package me.invkrh.raft.deploy

import scala.concurrent.Await

import akka.util.Timeout

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
      CoordinatorSystem.main(Array(raftConfigFilePath))
      (1 to 3) foreach { _ =>
        DaemonSystem.main(Array(raftConfigFilePath))
      }
      import scala.concurrent.duration._

      val refFuture = system
        .actorSelection("akka://coordinator-system@localhost:5000/user/raft-server-0")
        .resolveOne(5.seconds)

      Thread.sleep(5000)

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
