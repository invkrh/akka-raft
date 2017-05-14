package me.invkrh.raft.deploy.server

import org.scalatest.FlatSpecLike

class ServerSpawnerTest extends FlatSpecLike {
  "ServerSpawner" should "create system" in {
    val configFile = getClass.getResource("/server-0.properties").getPath
    val args = Array(configFile)
    ServerSpawner.main(args)
  }

  it should "throw exception when arg is empty" in {
    intercept[IllegalArgumentException] {
      ServerSpawner.main(Array())
    }
  }

  it should "throw exception when args are more than one " in {
    intercept[IllegalArgumentException] {
      ServerSpawner.main(Array("a", "b"))
    }
  }
}
