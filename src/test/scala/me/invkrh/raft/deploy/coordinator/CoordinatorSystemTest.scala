package me.invkrh.raft.deploy.coordinator

import org.scalatest.WordSpec

import me.invkrh.raft.exception.RaftConfigurationFileNotFoundException

class CoordinatorSystemTest extends WordSpec {
  "CoordinatorSystem" should {
    "throw RaftConfigurationFileNotFoundException when given file does not exist" in {
      intercept[RaftConfigurationFileNotFoundException]{
        CoordinatorSystem.main(Array("asdf"))
      }
    }
  }
}
