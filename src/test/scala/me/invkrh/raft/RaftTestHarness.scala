package me.invkrh.raft

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}

abstract class RaftTestHarness(val specName: String)
    extends TestKit(ActorSystem(specName))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
