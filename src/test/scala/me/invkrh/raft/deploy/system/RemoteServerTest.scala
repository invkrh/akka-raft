package me.invkrh.raft.deploy.system

import me.invkrh.raft.RaftTestHarness

class RemoteServerTest extends RaftTestHarness("RemoteServerSpec") { self =>
  "RemoteServer" should "throw exception when arg is empty" in {
    intercept[IllegalArgumentException] {
      RemoteServer.main(Array())
    }
  }

  it should "throw exception if some args are unknown" in {
    intercept[IllegalArgumentException] {
      RemoteServer.main(Array("a", "b"))
    }
  }
}
