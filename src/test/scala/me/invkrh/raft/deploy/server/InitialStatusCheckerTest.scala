package me.invkrh.raft.deploy.server

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike}

class InitialStatusCheckerTest
    extends TestKit(ActorSystem("SeverSpec"))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach { self =>

}
