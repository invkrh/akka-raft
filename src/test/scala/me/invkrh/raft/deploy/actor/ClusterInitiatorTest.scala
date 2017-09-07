package me.invkrh.raft.deploy.actor

import me.invkrh.raft.kit.RaftTestHarness
import me.invkrh.raft.message.DeployMessage._

class ClusterInitiatorTest extends RaftTestHarness("ClusterInitiatorTest") {
  "ClusterInitiator" should {
    "reject server id request when it saturates" in {
      val max = 5
      val initializerRef = system.actorOf(ClusterInitiator.props(max))
      for (i <- 1 to max) {
        initializerRef ! ServerIdRequest
        expectMsg(ServerId(i))
      }
      initializerRef ! ServerIdRequest
      expectMsg(ServerId(-1))
      system.stop(initializerRef)
    }
  }
}
