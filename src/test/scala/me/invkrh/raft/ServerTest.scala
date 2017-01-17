package me.invkrh.raft

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

import akka.actor.{ActorSystem, PoisonPill}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import me.invkrh.raft.RPCMessage._
import org.scalatest.{Entry => _, _}
import util.Metric

class ServerTest
    extends TestKit(ActorSystem("SeverSpec"))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Metric {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  override def afterEach(): Unit = {
    // Clean all available messages
    within(10 seconds) {
      while (msgAvailable) {
        expectMsgType[Any]
        Thread.sleep(10)
      }
    }
  }

  "Follower" should "return current term and success flag when AppendEntries is received" in {
    val serverActor = system.actorOf(Server.props(0, 100 millis))
    serverActor ! AppendEntries(0, 0, 0, 0, Seq[Entry](), 0)
    expectMsg(AppendEntriesResult(0, success = true))
    serverActor ! PoisonPill
  }

  it should "launch election after election timeout elapsed" in {
    val serverActor = system.actorOf(Server.props(0, 100 millis, members = ArrayBuffer(self)))
    expectMsgType[RequestVote]
    serverActor ! PoisonPill
  }

  it should "reset election timeout if AppendEntries msg is received" in {
    def heartbeatCheck(electionTimeout: FiniteDuration,
                       sleepTime: FiniteDuration,
                       heartbeat: Int) = {
      require(electionTimeout > sleepTime)
      val minDuration = sleepTime * heartbeat + electionTimeout
      val maxDuration = minDuration * 2
      val serverActor =
        system.actorOf(Server.props(0, electionTimeout, members = ArrayBuffer(self)))
      info(s"===> Execution between ${minDuration.toMillis} and ${maxDuration.toMillis} ms")
      within(minDuration, maxDuration) {
        Future {
          for (i <- 0 until heartbeat) {
            Thread.sleep(sleepTime.toMillis)
            serverActor ! AppendEntries(0, 0, 0, 0, Seq[Entry](), 0)
            info(s"heart beat $i is send")
          }
        }
        timer("Waiting for RequestVote") {
          fishForMessage(remaining, "election should have been launched") {
            case _: RequestVote => true
            case _: AppendEntriesResult =>
              info("heartbeat received")
              false
            case _ => false
          }
        }
      }
      serverActor ! PoisonPill
    }
    heartbeatCheck(0.2 seconds, 0.1 seconds, 6)
    heartbeatCheck(0.5 seconds, 0.3 seconds, 2)
  }

  it should "become leader when received messages of majority" in {
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val members = ArrayBuffer.apply(probe1.ref, probe2.ref)
    val server = system.actorOf(Server.props(0, 1 second, 0.5 second, members))

    probe1 expectMsg RequestVote(1, 0, 0, 0)
    probe2 expectMsg RequestVote(1, 0, 0, 0)
    probe1.send(server, RequestVoteResult(1, voteGranted = true))
    probe2.send(server, RequestVoteResult(1, voteGranted = false))

    probe1 expectMsg AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)
    probe2 expectMsg AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)
  }

}
