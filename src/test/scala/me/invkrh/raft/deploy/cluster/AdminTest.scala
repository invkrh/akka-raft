package me.invkrh.raft.deploy.cluster

import org.scalatest.FlatSpecLike

class AdminTest extends FlatSpecLike {
  "ServerLauncher" should "throw exception if no args are given" in {
    intercept[IllegalArgumentException] {
      Admin.main(Array())
    }
  }

  it should "throw exception if memberfile does not exist" in {
    intercept[RuntimeException] {
      Admin.main(Array("-f", "abc"))
    }
  }

  it should "throw exception if some args are unknown" in {
    intercept[IllegalArgumentException] {
      Admin.main(Array("-f", "abc", "-p", "remote"))
    }
  }
}
