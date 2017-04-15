package me.invkrh.raft

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

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

  def followerAndProbes: (ActorRef, Array[TestProbe]) = {
    createServerRefWithProbes(0, 150, 100, 1)
  }

  def candidateAndProbes: (ActorRef, Array[TestProbe]) = {
    val (server, probes) = createServerRefWithProbes(0, 150, 100, 5)
    probes.foreach(_ expectMsg RequestVote(1, 0, 0, 0))
    (server, probes)
  }

  def leaderAndProbes: (ActorRef, Array[TestProbe]) = {
    val (server, probes) = createServerRefWithProbes(0, 150, 100, 5)
    probes foreach { p =>
      p expectMsg RequestVote(1, 0, 0, 0)
      p.reply(RequestVoteResult(1, voteGranted = true))
    }
    (server, probes)
  }

  trait ProbeAction
  case class Exp(message: RPCMessage) extends ProbeAction // expect message
  case class Rep(message: RPCMessage) extends ProbeAction // reply message to last sender

  trait EndpointChecker {
    def serverAndProbes: (ActorRef, Array[TestProbe])
    def exchanges: List[ProbeAction]
    def run(): Unit = {
      val (server, probes) = serverAndProbes
      exchanges foreach {
        case Exp(msg) =>
          probes foreach (_ expectMsg msg)
        case Rep(msg) =>
          probes foreach (p => server.tell(msg, p.ref))
      }
      server ! PoisonPill
    }
  }

  class FollowerEndPointChecker(val exs: ProbeAction*) extends EndpointChecker {
    override val serverAndProbes: (ActorRef, Array[TestProbe]) = followerAndProbes
    override def exchanges: List[ProbeAction] = exs.toList
  }

  class CandidateEndPointChecker(val exs: ProbeAction*) extends EndpointChecker {
    override val serverAndProbes: (ActorRef, Array[TestProbe]) = candidateAndProbes
    override def exchanges: List[ProbeAction] = exs.toList
  }

  class LeaderEndPointChecker(val exs: ProbeAction*) extends EndpointChecker {
    override val serverAndProbes: (ActorRef, Array[TestProbe]) = leaderAndProbes
    override def exchanges: List[ProbeAction] = exs.toList
  }

  /////////////////////////////////////////////////
  //  Leader Election
  /////////////////////////////////////////////////

  // TODO: think of refactoring

  "Server" should "do nothing when no members are added" in {
    val server = system.actorOf(Server.props(0, 150, 100))
    expectNoMsg()
    server ! PoisonPill
  }

  "Follower" should "return current term and success flag when AppendEntries is received" in {
    new FollowerEndPointChecker(
      Rep(AppendEntries(0, 0, 0, 0, Seq[Entry](), 0)),
      Exp(AppendEntriesResult(0, success = true))
    ).run()
  }

  it should "reject AppendEntries when the term of the message is smaller than his own" in {
    new FollowerEndPointChecker(
      Rep(AppendEntries(-1, 0, 0, 0, Seq[Entry](), 0)),
      Exp(AppendEntriesResult(0, success = false))
    ).run()
  }

  it should "reply AppendEntries with larger term which is received with the message" in {
    new FollowerEndPointChecker(
      Rep(AppendEntries(2, 0, 0, 0, Seq[Entry](), 0)),
      Exp(AppendEntriesResult(2, success = true))
    ).run()
  }

  it should "reject RequestVote when the term of the message is smaller than his own" in {
    new FollowerEndPointChecker(
      Rep(RequestVote(-1, 0, 0, 0)),
      Exp(RequestVoteResult(0, voteGranted = false))
    ).run()
  }

  it should "reply RequestVote with (at least )larger term which is received with the message" in {
    new FollowerEndPointChecker(
      Rep(RequestVote(0, 0, 0, 0)),
      Exp(RequestVoteResult(0, voteGranted = true))
    ).run()
    new FollowerEndPointChecker(
      Rep(RequestVote(1, 0, 0, 0)),
      Exp(RequestVoteResult(1, voteGranted = true))
    ).run()
  }

  it should "reject RequestVote when it has already voted" in {
    new FollowerEndPointChecker(
      Rep(RequestVote(0, 0, 0, 0)),
      Exp(RequestVoteResult(0, voteGranted = true)),
      Rep(RequestVote(0, 1, 0, 0)),
      Exp(RequestVoteResult(0, voteGranted = false))
    ).run()
  }

  it should "launch election after election timeout elapsed" in {
    new FollowerEndPointChecker(
      Exp(RequestVote(1, 0, 0, 0))
    ).run()
  }

  it should "reset election timeout if AppendEntries msg is received" in {
    def heartbeatCheck(electionTimeoutInMS: Int, heartbeatIntervalInMS: Int, heartbeat: Int) = {
      val minDuration = heartbeatIntervalInMS * heartbeat + electionTimeoutInMS
      val maxDuration = minDuration * 2
      val server = system.actorOf(Server.props(0, electionTimeoutInMS, heartbeatIntervalInMS))
      server ! Join(self)
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
            case RequestVote(1, 0, 0, 0) => true
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
    new CandidateEndPointChecker(
      Rep(RequestVoteResult(1, voteGranted = true)),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0))
    ).run()
  }

  it should "become follower when received term in RequestVoteResult is larger than current term" in {
    new CandidateEndPointChecker(
      Rep(RequestVoteResult(2, voteGranted = true)),
      Exp(RequestVote(3, 0, 0, 0))
    ).run()
  }

  it should "become follower when received term in AppendEntriesResult is larger than current term" in {
    new CandidateEndPointChecker(
      Rep(AppendEntries(2, 0, 0, 0, Seq[Entry](), 0)),
      Exp(AppendEntriesResult(2, success = true)),
      Exp(RequestVote(3, 0, 0, 0))
    ).run()
  }

  it should "launch election of the next term when only minority granted" in {
    new CandidateEndPointChecker(
      Rep(RequestVoteResult(1, voteGranted = false)),
      Exp(RequestVote(2, 0, 0, 0))
    ).run()
  }

  "Leader" should "send heartbeat to every follower within heartbeat interval" in {
    new LeaderEndPointChecker(
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(1, success = true)),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(1, success = true)),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(1, success = true))
    ).run()
  }

  it should "become follower if the received term of AppendEntriesResult is larger than current term" in {
    new LeaderEndPointChecker(
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(2, success = true)),
      Exp(RequestVote(3, 0, 0, 0))
    ).run()
  }

}
