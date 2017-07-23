package me.invkrh.raft.core

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.pattern.ask
import akka.util.Timeout

import me.invkrh.raft.kit.RaftTestHarness
import me.invkrh.raft.message.{Command, CommandResponse, Membership}

class ServerClusterTest extends RaftTestHarness("ClusterSpec") { self =>

  "Server cluster" should {
    "work well" in {
      val num = 5
      val memberDict = List
        .tabulate(num) { i =>
          val electTime = 150 + 50 * i millis
          val ref = Server.run(i, electTime, electTime, 100 millis, s"svr-$i")
          i -> ref
        }
        .toMap

      memberDict foreach {
        case (_, serverRef) => serverRef ! Membership(memberDict)
      }

      implicit val timeout = Timeout(5 seconds)
      val future = (memberDict.apply(1) ? Command("x", 1)).mapTo[CommandResponse]
      assertResult(CommandResponse(success = true)) {
        Await.result(future, 5 seconds)
      }
    }
  }

}
