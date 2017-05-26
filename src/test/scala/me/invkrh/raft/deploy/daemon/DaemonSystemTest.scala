package me.invkrh.raft.deploy.daemon

import org.scalatest.WordSpec

import me.invkrh.raft.exception.RaftConfigurationFileNotFoundException

class DaemonSystemTest extends WordSpec {
  "DaemonSystem" should {
    "throw RaftConfigurationFileNotFoundException when given file does not exist" in {
      intercept[RaftConfigurationFileNotFoundException]{
        DaemonSystem.main(Array("asdf"))
      }
    }
  }
}
