package me.invkrh.raft.deploy

import me.invkrh.raft.RaftTestHarness
import me.invkrh.raft.deploy.cluster.Admin
import me.invkrh.raft.deploy.system.RemoteServer

class IntegrationTest extends RaftTestHarness("IntegrationSpec") { self =>

  "Cluster" should "be launched correctly" in {
    (1 to 3) foreach { i =>
      val configFilePath = getClass.getResource(s"/server-$i.properties").getPath
      RemoteServer.main(Array("-f", configFilePath))
    }
    val memberFilePath = getClass.getResource(s"/members").getPath
    Admin.main(Array("init", "-f", memberFilePath))
    Thread.sleep(3000)
    Admin.main(Array("stop", "-f", memberFilePath))
  }

}
