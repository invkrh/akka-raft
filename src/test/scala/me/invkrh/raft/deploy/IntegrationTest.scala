package me.invkrh.raft.deploy

import org.scalatest.WordSpec

import me.invkrh.raft.deploy.coordinator.CoordinatorSystem
import me.invkrh.raft.deploy.daemon.DaemonSystem

class IntegrationTest extends WordSpec {

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
      Thread.sleep(10000)
      // TODO: until leader elected
    }
  }
}
