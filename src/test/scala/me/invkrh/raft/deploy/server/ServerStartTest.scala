package me.invkrh.raft.deploy.server

import org.scalatest.FlatSpecLike

class ServerStartTest extends FlatSpecLike {

  "ServerSpawner" should "create system" in {
    val configFile = getClass.getResource("/server-1.properties").getPath
    ServerStart.main(Array("-f", configFile))
  }

  it should "throw exception when arg is empty" in {
    intercept[IllegalArgumentException] {
      ServerStart.main(Array())
    }
  }

  it should "throw exception if some args are unknown" in {
    intercept[IllegalArgumentException] {
      ServerStart.main(Array("a", "b"))
    }
  }
}
