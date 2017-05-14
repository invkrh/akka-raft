package me.invkrh.raft.deploy.server

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.FlatSpecLike

class ServerIntegrationTest extends TestKit(ActorSystem("SeverSpec")) with FlatSpecLike {
  "Cluster" should "be launched correctly" in {

    (0 to 2) foreach { i =>
      val configFile = getClass.getResource(s"/server-$i.properties").getPath
      val args = Array(configFile)
      ServerSpawner.main(args)
    }
    ServerLauncher.main(Array("-b", "localhost:5000,localhost:5001,localhost:5002"))
    Thread.sleep(10000)
  }

}
