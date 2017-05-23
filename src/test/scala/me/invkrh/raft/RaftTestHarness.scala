package me.invkrh.raft

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import me.invkrh.raft.deploy.RemoteProvider
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

object RaftTestHarness {
  def localSystem(name: String): ActorSystem = ActorSystem(name)
  def remoteSystem(name: String): ActorSystem = {
    new RemoteProvider {}
      .createSystem(systemName = name)
  }
  def testSystem(name: String, withRemote: Boolean): ActorSystem = {
    if (withRemote) {
      remoteSystem(name)
    } else {
      localSystem(name)
    }
  }
}

abstract class RaftTestHarness(specName: String, withRemote: Boolean = false)
    extends TestKit(RaftTestHarness.testSystem(specName, withRemote))
    with ImplicitSender
    with WordSpecLike
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
