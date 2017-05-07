package me.invkrh.raft

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import me.invkrh.raft.core.Message.{Command, CommandResponse, Init}
import me.invkrh.raft.core.Server
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike}

class ClusterTest
    extends TestKit(ActorSystem("ClusterSpec"))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  override def afterEach(): Unit = {}

  "Cluster" should "work well" in {
    val num = 5
    val memberDict = List
      .tabulate(num) { i =>
        val electTime = 150 + 50 * i millis
        val ref = Server.run(i, electTime, electTime, 100 millis, s"svr-$i")
        i -> ref
      }
      .toMap
    
    memberDict foreach {
      case (_, serverRef) => serverRef ! Init(memberDict)
    }
    
    implicit val timeout = Timeout(5 seconds)
    val future = (memberDict.apply(1) ? Command("x", 1)).mapTo[CommandResponse]
    assertResult(CommandResponse(success = true)) {
      Await.result(future, 5 seconds)
    }
  }

}
