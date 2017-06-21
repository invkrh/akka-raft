package me.invkrh.raft.deploy

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.pattern.ask
import akka.util.Timeout

import me.invkrh.raft.deploy.remote.{Initiator, Launcher}
import me.invkrh.raft.kit.TestHarness
import me.invkrh.raft.message.{GetStatus, Status}
import me.invkrh.raft.util.ActorRefUtils

class IntegrationTest extends TestHarness { self =>

  /**
   * This test will use remote system define in the main function
   */
  "Entire system" should {
    "be launched correctly" in {

      /**
       * Start precursor
       */
      val initiator = new Initiator()
      initiator.main(Array())
      Thread.sleep(5000) // Wait for initiator to start

      /**
       * Add two more server
       */
      val serverNum = config.getInt("cluster.quorum") * 2 - 1
      val launchers = 1 to serverNum map { i =>
        new Launcher()
      }

      launchers.zipWithIndex foreach {
        case (launcher, index) =>
          launcher.main(Array("--port", 4242 + index + "", "--init", initiator.address))
      }
      Thread.sleep(10000) // Wait for leader election

      implicit val timeoutDuration = 5.seconds
      implicit val timeout = Timeout(timeoutDuration)

      val firstLauncher = launchers.head
      val serverRef =
        ActorRefUtils.resolveRefByName(
          firstLauncher.systemName,
          firstLauncher.address,
          raftServerName + "*"
        )(firstLauncher.system, timeoutDuration)

      /**
       * Check leader has been elected
       */
      assertResult(true) {
        val status = Await.result((serverRef ? GetStatus).mapTo[Status], timeoutDuration)
        logInfo("Status = " + status)
        status.leader.nonEmpty
      }

      /**
       * Stop systems
       */
      launchers foreach { l =>
        l.system.terminate()
        // scalastyle:off
        Await.ready(l.system.whenTerminated, Duration.Inf)
        // scalastyle:on
        logInfo(s"Launcher at ${l.address} has been shut down")
      }
    }
  }
}
