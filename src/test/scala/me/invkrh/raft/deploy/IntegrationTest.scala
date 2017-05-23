package me.invkrh.raft.deploy

import me.invkrh.raft.deploy.bootstrap.BootstrapSystem
import me.invkrh.raft.deploy.daemon.DaemonSystem
import org.scalatest.WordSpec

class IntegrationTest extends WordSpec {
  "Cluster" should {
    "be launched correctly" in {
      val configFilePath = getClass.getResource(s"/bootstrap").getPath
      val serverConfigFilePath = getClass.getResource(s"/server.properties").getPath
      BootstrapSystem.main(Array(configFilePath, "3"))
      (1 to 3) foreach { _ =>
        DaemonSystem.main(Array(configFilePath, serverConfigFilePath))
      }
      Thread.sleep(10000)
      // TODO: until leader elected
    }
  }
}
