package me.invkrh.raft.deploy.server

import java.io.File

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.scalatest.FlatSpecLike

class ServerIntegrationTest extends TestKit(ActorSystem("IntegrationSpec")) with FlatSpecLike {

  "Cluster" should "be launched correctly" in {
    (1 to 3) foreach { i =>
      val configFilePath = getClass.getResource(s"/server-$i.properties").getPath
      ServerStart.main(Array("-f", configFilePath))
    }
    val memberFilePath = getClass.getResource(s"/members").getPath
    RaftInit.main(Array("-f", memberFilePath))
    Thread.sleep(10000)
  }

}
