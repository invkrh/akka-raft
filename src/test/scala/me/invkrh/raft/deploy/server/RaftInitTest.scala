package me.invkrh.raft.deploy.server

import org.scalatest.FlatSpecLike

class RaftInitTest extends FlatSpecLike {

  "ServerLauncher" should "throw exception if no args are given" in {
    intercept[IllegalArgumentException] {
      RaftInit.main(Array())
    }
  }

  it should "throw exception if memberfile does not exist" in {
    intercept[IllegalArgumentException] {
      RaftInit.main(Array("-f", "abc"))
    }
  }

  it should "throw exception if some args are unknown" in {
    intercept[IllegalArgumentException] {
      RaftInit.main(Array("-f", "members", "-p", "remote"))
    }
  }
}
