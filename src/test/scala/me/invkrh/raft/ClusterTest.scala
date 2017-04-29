package me.invkrh.raft

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import me.invkrh.raft.core.Message.{ClientMessage, Command, CommandAccepted}
import me.invkrh.raft.core.Server
import me.invkrh.raft.util.Metric
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike}

class ClusterTest
    extends TestKit(ActorSystem("ClusterSpec"))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Metric {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  override def afterEach(): Unit = {}

  "Cluster" should "work well" in {
    val num = 5
    val memberDict =
      List.tabulate(num)(i => i -> s"akka://ClusterSpec/user/svr-$i").toMap
    val quorum = List
      .tabulate(num) { i =>
        val electTime = 150 + 50 * i millis
        val ref = Server.run(i, electTime, electTime, 100 millis, memberDict, s"svr-$i")
        i -> ref
      }
      .toMap
    implicit val timeout = Timeout(5 seconds)
    val future = (quorum(1) ? Command("x", 1)).mapTo[ClientMessage]
    assertResult(CommandAccepted()) {
      Await.result(future, 5 seconds)
    }
  }

}
