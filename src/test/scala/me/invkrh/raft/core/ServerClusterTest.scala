package me.invkrh.raft.core

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.pattern.ask
import akka.util.Timeout

import me.invkrh.raft.kit.RaftTestHarness
import me.invkrh.raft.message.AdminMessage._
import me.invkrh.raft.message.ClientMessage._
import me.invkrh.raft.storage.MemoryStore

class ServerClusterTest extends RaftTestHarness("ClusterSpec") { self =>

  "Server cluster" should {
    "work well" in {
      val num = 5
      val memberDict = List
        .tabulate(num) { i =>
          val ref = Server.run(i, 1000 millis, 1500 millis, 100 millis, MemoryStore(), s"svr-$i")
          i -> ref
        }
        .toMap

      memberDict foreach {
        case (_, serverRef) => serverRef ! Membership(memberDict)
      }

      implicit val timeout = Timeout(3 seconds)
      val future = (memberDict.apply(1) ? SET("x", 1)).mapTo[CommandResult]
      assertResult(CommandSuccess(None)) {
        Await.result(future, 3 seconds)
      }
    }
  }

}
