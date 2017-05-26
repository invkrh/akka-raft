package me.invkrh.raft.deploy.coordinator

import me.invkrh.raft.message.{ServerId, ServerIdRequest}
import me.invkrh.raft.RaftTestHarness

class ServerInitializerTest extends RaftTestHarness("ServerInitializerTest") {
  "ServerInitializer" should {
    "reject server id request when it saturates" in {
      val max = 5
      val initializerRef = system.actorOf(ServerInitializer.props(max))
      for(i <- 0 until max) {
        initializerRef ! ServerIdRequest
        expectMsg(ServerId(i))
      }
      initializerRef ! ServerIdRequest
      expectMsg(ServerId(-1))
      system.stop(initializerRef)
    }
  }
}
