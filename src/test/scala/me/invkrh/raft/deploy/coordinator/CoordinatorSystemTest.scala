package me.invkrh.raft.deploy.coordinator

import org.scalatest.WordSpec

class CoordinatorSystemTest extends WordSpec {
  "CoordinatorSystem" should {
    "throw RuntimeException when given file does not exist" in {
      intercept[RuntimeException]{
        CoordinatorSystem.main(Array("asdf"))
      }
    }
  }
}
