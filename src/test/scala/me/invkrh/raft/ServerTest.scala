package me.invkrh.raft

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

import akka.actor.{ActorSystem, PoisonPill}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest._
import util.Metric
import RPCMessage.{AppendEntries, AppendEntriesResult, Entry, RequestVote}

class ServerTest
    extends TestKit(ActorSystem("SeverSpec"))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with Metric {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val dummyAppendEntriesMessage = AppendEntries(0, "", 0, 0, Seq[Entry](), 0)

  "Raft Server" should "return current term and success flag when AppendEntries is received" in {
    val serverActor = system.actorOf(Server.props(1 second, Seq()))
    serverActor ! dummyAppendEntriesMessage
    expectMsgType[AppendEntriesResult]
  }

  it should "launch election after election timeout elapsed" in {
    system.actorOf(Server.props(2 second, Seq(self)))
    expectMsgType[RequestVote]
  }

  it should "reset election timeout if AppendEntries msg is received" in {
    def heartbeatCheck(electionTimeout: FiniteDuration,
                       sleepTime: FiniteDuration,
                       heartbeat: Int) = {
      val minDuration = sleepTime * heartbeat + electionTimeout
      val maxDuration = minDuration + sleepTime
      val serverActor = system.actorOf(Server.props(electionTimeout, Seq(self)))
      info(s"===> Execution between ${minDuration.toMillis} and ${maxDuration.toMillis} ms")
      within(minDuration, maxDuration) {
        Future {
          for (i <- 0 until heartbeat) {
            Thread.sleep(sleepTime.toMillis)
            serverActor ! dummyAppendEntriesMessage
            info(s"heart beat $i is send")
          }
        }
        timer("Waiting for RequestVote") {
          fishForMessage(remaining, "election should have been launched") {
            case _: RequestVote => true
            case _: AppendEntriesResult =>
              info("heartbeat received")
              false
          }
        }
      }
      serverActor ! PoisonPill
    }
    heartbeatCheck(2 seconds, 1 seconds, 6)
    heartbeatCheck(5 seconds, 3 seconds, 2)
    heartbeatCheck(10 seconds, 6 seconds, 1)
  }

}
