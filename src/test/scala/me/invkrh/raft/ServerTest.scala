package me.invkrh.raft

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import me.invkrh.raft.Message._
import me.invkrh.raft.util.Metric
import org.scalatest.{Entry => _, _}

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

  def createServerRef(id: Int, electTimeout: Int, hbInterval: Int): ActorRef = {
    val server = system.actorOf(Server.props(id, electTimeout, hbInterval))
    server ! Join(self)
    server
  }

  def createServerRefWithProbes(id: Int,
                                electTimeout: Int,
                                hbInterval: Int,
                                probeNum: Int): (ActorRef, Array[TestProbe]) = {
    val probes = Array.tabulate(probeNum)(_ => TestProbe())
    val members = probes.map(_.ref)
    val server = system.actorOf(Server.props(id, electTimeout, hbInterval))
    server ! Join(members: _*)
    (server, probes)
  }

  def endpointCheck(in: RPCMessage, out: RPCMessage): Unit = {
    val server = createServerRef(0, 150, 100)
    server ! in
    expectMsg(out)
    server ! PoisonPill
  }

  def endpointCheck(in: List[RPCMessage], out: List[RPCMessage]): Unit = {
    val server = createServerRef(0, 150, 100)
    (in zip out) foreach {
      case (q, a) =>
        server ! q
        expectMsg(a)
    }
    server ! PoisonPill
  }

  /////////////////////////////////////////////////
  //  Leader Election
  /////////////////////////////////////////////////
  
  "Server" should "do nothing when no members are added" in {
    val server = system.actorOf(Server.props(0, 150, 100))
    expectNoMsg()
    server ! PoisonPill
  }

  "Follower" should "return current term and success flag when AppendEntries is received" in {
    endpointCheck(AppendEntries(0, 0, 0, 0, Seq[Entry](), 0),
                  AppendEntriesResult(0, success = true))
  }

  it should "reject AppendEntries when the term of the message is smaller than his own" in {
    endpointCheck(AppendEntries(-1, 0, 0, 0, Seq[Entry](), 0),
                  AppendEntriesResult(0, success = false))
  }

  it should "reply AppendEntries with larger term which is received with the message" in {
    endpointCheck(AppendEntries(2, 0, 0, 0, Seq[Entry](), 0),
                  AppendEntriesResult(2, success = true))
  }

  it should "reject RequestVote when the term of the message is smaller than his own" in {
    endpointCheck(RequestVote(-1, 0, 0, 0), RequestVoteResult(0, voteGranted = false))
  }

  it should "reply RequestVote with (at least )larger term which is received with the message" in {
    endpointCheck(RequestVote(0, 0, 0, 0), RequestVoteResult(0, voteGranted = true))
    endpointCheck(RequestVote(1, 0, 0, 0), RequestVoteResult(1, voteGranted = true))
  }

  it should "reject RequestVote when it has already voted" in {
    endpointCheck(
      List(RequestVote(0, 0, 0, 0), RequestVote(0, 1, 0, 0)),
      List(RequestVoteResult(0, voteGranted = true), RequestVoteResult(0, voteGranted = false)))
  }

  it should "launch election after election timeout elapsed" in {
    val server = createServerRef(0, 150, 100)
    expectMsgType[RequestVote]
    server ! PoisonPill
  }

  it should "reset election timeout if AppendEntries msg is received" in {
    def heartbeatCheck(electionTimeoutInMS: Int, heartbeatIntervalInMS: Int, heartbeat: Int) = {
      val minDuration = heartbeatIntervalInMS * heartbeat + electionTimeoutInMS
      val maxDuration = minDuration * 2
      val server = createServerRef(0, electionTimeoutInMS, heartbeatIntervalInMS)
      info(s"===> Execution between $minDuration and $maxDuration ms")
      within(minDuration milliseconds, maxDuration milliseconds) {
        Future {
          for (i <- 0 until heartbeat) {
            Thread.sleep(heartbeatIntervalInMS)
            server ! AppendEntries(0, 0, 0, 0, Seq[Entry](), 0)
            info(s"heart beat $i is send")
          }
        }
        timer("Waiting for RequestVote") {
          fishForMessage(remaining, "election should have been launched") {
            case _: RequestVote => true
            case AppendEntriesResult(0, true) =>
              info("heartbeat received")
              false
            case _ => false
          }
        }
      }
      server ! PoisonPill
    }
    heartbeatCheck(200, 100, 6)
    heartbeatCheck(500, 300, 2)
  }

  "Candidate" should "become leader when received messages of majority" in {
    val (server, probes) = createServerRefWithProbes(0, 1000, 500, 5)
    probes.zipWithIndex.foreach {
      case (p, idx) =>
        p expectMsg RequestVote(1, 0, 0, 0)
        p.reply(RequestVoteResult(1, voteGranted = idx >= probes.length / 2)) // majority
    }
    probes.foreach { p =>
      p expectMsg AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)
    }
    server ! PoisonPill
  }

  it should "become follower when received term in RequestVoteResult is larger than current term" in {
    val (server, probes) = createServerRefWithProbes(0, 1000, 500, 5)
    probes foreach { p =>
      p expectMsg RequestVote(1, 0, 0, 0)
      p.reply(RequestVoteResult(2, voteGranted = true))
    }

    probes foreach { p =>
      p expectMsg RequestVote(3, 0, 0, 0)
    }
    server ! PoisonPill
  }

  it should "become follower when received term in AppendEntriesResult is larger than current term" in {
    val (server, probes) = createServerRefWithProbes(0, 1000, 500, 5)
    probes foreach { p =>
      p expectMsg RequestVote(1, 0, 0, 0)
      p.reply(AppendEntries(2, 0, 0, 0, Seq[Entry](), 0))
    }
    probes foreach { p =>
      p expectMsg AppendEntriesResult(2, success = true)
    }
    probes foreach { p =>
      p expectMsg RequestVote(3, 0, 0, 0)
    }
    server ! PoisonPill
  }

  it should "launch election of the next term when only minority granted" in {
    val (server, probes) = createServerRefWithProbes(0, 1000, 500, 5)
    probes.zipWithIndex.foreach {
      case (p, idx) =>
        p expectMsg RequestVote(1, 0, 0, 0)
        p.reply(RequestVoteResult(1, voteGranted = idx < probes.length / 2)) // minority
    }
    probes.foreach { p =>
      p expectMsg RequestVote(2, 0, 0, 0)
    }
    server ! PoisonPill
  }

  "Leader" should "send heartbeat to every follower within heartbeat interval" in {
    val (server, probes) = createServerRefWithProbes(0, 1000, 500, 5)
    probes foreach { p =>
      p expectMsg RequestVote(1, 0, 0, 0)
      p.reply(RequestVoteResult(1, voteGranted = true))
    }
    (1 to 3).foreach { _ =>
      probes.foreach { p =>
        p expectMsg AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)
        p.reply(AppendEntriesResult(1, true))
      }
    }
    server ! PoisonPill
  }

  it should "become follower if the received term of AppendEntriesResult is larger than current term" in {
    val (server, probes) = createServerRefWithProbes(0, 1000, 500, 5)
    probes foreach { p =>
      p expectMsg RequestVote(1, 0, 0, 0)
      p.reply(RequestVoteResult(1, voteGranted = true))
    }
    probes foreach { p =>
      p expectMsg AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)
      p reply AppendEntriesResult(2, success = true)
    }
    probes foreach { p =>
      p expectMsg RequestVote(3, 0, 0, 0)
    }
    server ! PoisonPill
  }

}
