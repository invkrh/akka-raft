package me.invkrh.raft.deploy

import me.invkrh.raft.deploy.remote.PrecursorSystemResolver
import me.invkrh.raft.kit.RaftTestHarness

class IntegrationTest extends RaftTestHarness("IntegrationSpec", true) { // TestHarness ?

  /**
   * This test will use remote system define in the main function
   */
  "Entire system" should {
    "be launched correctly" in {

      /**
       * Start precursor
       */
      Daemon(config, "init")

      /**
       * Add two more server
       */
      Thread.sleep(10000) // Wait for initializer start
      val nonPrecursorServerNumber = config.getInt("cluster.quorum") * 2 - 1 - 1
      1 to nonPrecursorServerNumber foreach { _ =>
        Daemon(config, "join")
      }

      /**
       * Get leader id after election
       */
      Thread.sleep(5000) // Wait for leader election
      val precursorAddress = config.getString("cluster.precursor")
      assertResult(true) {
        new PrecursorSystemResolver(precursorAddress).leaderID.nonEmpty
      }

      /**
       * Stop all servers
       */
      Daemon(config, "stop-all")
    }
  }
}
