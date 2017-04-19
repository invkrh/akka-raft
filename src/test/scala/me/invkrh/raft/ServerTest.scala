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

  override def afterEach(): Unit = {}

  trait ProbeAction
  case class Exp(message: RPCMessage) extends ProbeAction // expect message
  case class Tel(message: RPCMessage) extends ProbeAction // tell message
  case class Rep(message: RPCMessage) extends ProbeAction // reply message for ask pattern
  // majority of the probes reply
  case class MajorRep(message: RPCMessage, otherMsg: Option[RPCMessage] = None) extends ProbeAction
  // minority of the probes reply
  case class MinorRep(message: RPCMessage, otherMsg: Option[RPCMessage] = None) extends ProbeAction

  trait EndpointChecker {
    protected def clusterSetup(server: ActorRef, probes: Array[TestProbe]): Unit
    protected def exchanges: List[ProbeAction]

    private def createServerRefWithProbes(id: Int,
                                          electTimeout: Int,
                                          hbInterval: Int,
                                          probeNum: Int): (ActorRef, Array[TestProbe]) = {
      val probes = Array.tabulate(probeNum)(_ => TestProbe())
      val members = probes.map(_.ref)
      val server = system.actorOf(Server.props(id, electTimeout, hbInterval))
      server ! Join(members: _*)
      (server, probes)
    }
    def run(probeNum: Int = 1): Unit = {
      val (server, probes) = createServerRefWithProbes(0, 150, 100, probeNum)
      clusterSetup(server, probes)
      exchanges foreach {
        case Exp(msg) =>
          probes foreach (_ expectMsg msg)
        case Tel(msg) =>
          // tell make destination coherent with logic,
          // however it does not work with ask pattern in which the sender is not server,
          // but a sender under /temp
          probes foreach (p => server.tell(msg, p.ref))
        case Rep(msg) =>
          /**
           *  Actor path under /temp is the guardian for all short-lived system-created actors,
           *  e.g. those which are used in the implementation of ActorRef.ask
           */
          // reply makes all ask pattern work, however it will not send back msg to server
          // since its sender is under /temp (not server) which is used for ask pattern.
          probes foreach (_ reply msg)
        case MajorRep(msg, optMsg) =>
          val maj = probes.length / 2 + 1
          probes.take(maj) foreach (_ reply msg)
          optMsg match {
            case Some(other) => probes.drop(maj) foreach (_ reply other)
            case None =>
          }
        case MinorRep(msg, optMsg) =>
          val min = probes.length / 2 - 1
          probes.take(min) foreach (_ reply msg)
          optMsg match {
            case Some(other) => probes.drop(min) foreach (_ reply other)
            case None =>
          }
      }

      probes foreach (_.ref ! PoisonPill)

      /**
       * Both stop and PoisonPill will terminate the actor and stop the message queue.
       * They will cause the actor to cease processing messages, send a stop call to all
       * its children, wait for them to terminate, then call its postStop hook.
       * All further messages are sent to the dead letters mailbox.
       *
       * The difference is in which messages get processed before this sequence starts.
       * In the case of the `stop` call, the message currently being processed is completed first,
       * with all others discarded. When sending a PoisonPill, this is simply another message
       * in the queue, so the sequence will start when the PoisonPill is received.
       * All messages that are ahead of it in the queue will be processed first.
       */
      system.stop(server)
    }
  }

  class FollowerEndPointChecker(val exs: ProbeAction*) extends EndpointChecker {
    override def exchanges: List[ProbeAction] = exs.toList
    override def clusterSetup(server: ActorRef, probes: Array[TestProbe]): Unit = {}
  }

  class CandidateEndPointChecker(val exs: ProbeAction*) extends EndpointChecker {
    override def exchanges: List[ProbeAction] = exs.toList
    override def clusterSetup(server: ActorRef, probes: Array[TestProbe]): Unit = {
      probes.foreach(_ expectMsg RequestVote(1, 0, 0, 0))
    }
  }

  class LeaderEndPointChecker(val exs: ProbeAction*) extends EndpointChecker {
    override def exchanges: List[ProbeAction] = exs.toList
    override def clusterSetup(server: ActorRef, probes: Array[TestProbe]): Unit = {
      probes foreach { p =>
        p expectMsg RequestVote(1, 0, 0, 0)
        p.reply(RequestVoteResult(1, voteGranted = true))
      }
    }
  }

  // TODO: Add integration test
  // TODO: Test time assertion

  /////////////////////////////////////////////////
  //  Leader Election
  /////////////////////////////////////////////////

  "Server" should "do nothing when no members are added" in {
    val server = system.actorOf(Server.props(0, 150, 100))
    expectNoMsg()
    server ! PoisonPill
  }

  "Follower" should "return current term and success flag when AppendEntries is received" in {
    new FollowerEndPointChecker(
      Tel(AppendEntries(0, 0, 0, 0, Seq[Entry](), 0)),
      Exp(AppendEntriesResult(0, success = true))
    ).run()
  }

  it should "reject AppendEntries when the term of the message is smaller than his own" in {
    new FollowerEndPointChecker(
      Tel(AppendEntries(-1, 0, 0, 0, Seq[Entry](), 0)),
      Exp(AppendEntriesResult(0, success = false))
    ).run()
  }

  it should "reply AppendEntries with larger term which is received with the message" in {
    new FollowerEndPointChecker(
      Tel(AppendEntries(2, 0, 0, 0, Seq[Entry](), 0)),
      Exp(AppendEntriesResult(2, success = true))
    ).run()
  }

  it should "reject RequestVote when the term of the message is smaller than his own" in {
    new FollowerEndPointChecker(
      Tel(RequestVote(-1, 0, 0, 0)),
      Exp(RequestVoteResult(0, voteGranted = false))
    ).run()
  }

  it should "reply RequestVote with (at least )larger term which is received with the message" in {
    new FollowerEndPointChecker(
      Tel(RequestVote(0, 0, 0, 0)),
      Exp(RequestVoteResult(0, voteGranted = true))
    ).run()
    new FollowerEndPointChecker(
      Tel(RequestVote(1, 0, 0, 0)),
      Exp(RequestVoteResult(1, voteGranted = true))
    ).run()
  }

  it should "reject RequestVote when it has already voted" in {
    new FollowerEndPointChecker(
      Tel(RequestVote(0, 0, 0, 0)),
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
      MajorRep(RequestVoteResult(1, voteGranted = true)),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0))
    ).run(5)
  }

  it should "launch election of the next term when only minority granted" in {
    new CandidateEndPointChecker(
      MinorRep(RequestVoteResult(1, voteGranted = true),
               Some(RequestVoteResult(1, voteGranted = false))),
      Exp(RequestVote(2, 0, 0, 0))
    ).run(5)
  }

  it should "become follower when the received term in RequestVoteResult is larger than " +
    "current term" in {
    new CandidateEndPointChecker(
      Rep(RequestVoteResult(2, voteGranted = true)),
      Exp(RequestVote(3, 0, 0, 0))
    ).run(5)
  }

  it should "become follower when received term in AppendEntriesResult is larger than " +
    "current term" in {
    new CandidateEndPointChecker(
      Tel(AppendEntries(2, 0, 0, 0, Seq[Entry](), 0)),
      Exp(AppendEntriesResult(2, success = true)),
      Exp(RequestVote(3, 0, 0, 0))
    ).run(5)
  }

  "Leader" should "send heartbeat to every follower within heartbeat interval" in {
    new LeaderEndPointChecker(
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(1, success = true)),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(1, success = true)),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(1, success = true))
    ).run(5)
  }

  it should "become follower if the received term of AppendEntriesResult is larger than " +
    "current term" in {
    new LeaderEndPointChecker(
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(2, success = true)),
      Exp(RequestVote(3, 0, 0, 0))
    ).run(5)
  }

  it should "continue to distribute heartbeat when AppendEntry requests are rejected" in {
    new LeaderEndPointChecker(
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      MinorRep(AppendEntriesResult(1, success = false),
               Some(AppendEntriesResult(1, success = true))),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      MinorRep(AppendEntriesResult(1, success = true),
               Some(AppendEntriesResult(1, success = false))),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(1, success = true))
    ).run(5)
  }

  it should "continue to distribute heartbeat when some heartbeat acks are not received" in {
    new LeaderEndPointChecker(
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      MinorRep(AppendEntriesResult(1, success = false)),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      MajorRep(AppendEntriesResult(1, success = true)),
      Exp(AppendEntries(1, 0, 0, 0, Seq[Entry](), 0)),
      Rep(AppendEntriesResult(1, success = true))
    ).run(5)
  }

}
