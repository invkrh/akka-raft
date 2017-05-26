package me.invkrh.raft.deploy.daemon

import org.scalatest.WordSpec

class DaemonSystemTest extends WordSpec {
  "DaemonSystem" should {
    "throw RuntimeException when given file does not exist" in {
      intercept[RuntimeException]{
        DaemonSystem.main(Array("asdf"))
      }
    }
  }
}
