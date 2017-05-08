package me.invkrh.raft

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ActorSystem, PoisonPill}
import akka.testkit.{ImplicitSender, TestKit}
import me.invkrh.raft.core.Exception.{HeartbeatIntervalException, LeaderNotUniqueException}
import me.invkrh.raft.core.Message._
import me.invkrh.raft.core.{Server, State}
import me.invkrh.raft.kit._
import org.scalatest._

class ServerTest
    extends TestKit(ActorSystem("SeverSpec"))
    with ImplicitSender
    with FlatSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach { self =>

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  override def afterEach(): Unit = {}

  implicit val tk: TestKit = this

  /////////////////////////////////////////////////
  //  Leader Election
  /////////////////////////////////////////////////

  "Server" should "throw exception when election time is shorter than heartbeat interval" in {
    intercept[HeartbeatIntervalException] {
      val server =
        system.actorOf(Server.props(0, 100 millis, 100 millis, 150 millis), "svr")
      server ! PoisonPill
    }
  }

  it should "start if none of the bootstrap members are resolved" in {
    val server =
      system.actorOf(Server.props(0, 150 millis, 150 millis, 100 millis))
    expectNoMsg()
    server ! PoisonPill
  }

  "Follower" should "accept AppendEntries when the term of the message is equal to his own" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(AppendEntries(0, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(0, success = true))
      )
      .run()
  }

  it should "reject AppendEntries when the term of the message is smaller than his own" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(AppendEntries(-1, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(0, success = false))
      )
      .run()
  }

  it should "reply AppendEntries with larger term which is received with the message" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(AppendEntries(2, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(2, success = true))
      )
      .run()
  }

  it should "reject RequestVote when the term of the message is equal to his own" in {
    new FollowerEndPointChecker()
      .setActions(Tell(RequestVote(0, 0, 0, 0)), Expect(RequestVoteResult(0, success = false)))
      .run()
  }

  it should "reject RequestVote when the term of the message is smaller than his own" in {
    new FollowerEndPointChecker()
      .setActions(Tell(RequestVote(-1, 0, 0, 0)), Expect(RequestVoteResult(0, success = false)))
      .run()
  }

  it should "accept RequestVote when the term of the message is (at least) larger than his own" in {
    new FollowerEndPointChecker()
      .setActions(Tell(RequestVote(10, 0, 0, 0)), Expect(RequestVoteResult(10, success = true)))
      .run()
  }

  it should "reject RequestVote when it has already voted" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(RequestVote(1, 0, 0, 0)),
        Expect(RequestVoteResult(1, success = true)),
        Reply(RequestVote(1, 1, 0, 0)),
        Expect(RequestVoteResult(1, success = false))
      )
      .run()
  }

  it should "launch election after election timeout elapsed" in {
    val checker = new FollowerEndPointChecker()
    checker
      .setActions(Expect(RequestVote(1, checker.getId, 0, 0)))
      .run()
  }

  it should "reset election timeout if AppendEntries msg is received" in {
    val electionTime = 150.millis
    val tickTime = 100.millis
    val heartbeatNum = 3
    val checker = new FollowerEndPointChecker()
    checker
      .setElectionTime(electionTime)
      .setTickTime(tickTime)
      .setActions(
        Within(
          tickTime * heartbeatNum + electionTime,
          tickTime * heartbeatNum + electionTime * 2,
          Rep(
            heartbeatNum,
            Delay(tickTime, Tell(AppendEntries(0, checker.getId + 1, 0, 0, Seq[LogEntry](), 0))),
            Expect(AppendEntriesResult(0, success = true))
          ),
          Expect(RequestVote(1, checker.getId, 0, 0))
        )
      )
      .run()
  }

  it should "resend command to leader if leader is elected" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(AppendEntries(2, 1, 0, 0, Seq[LogEntry](), 0)), // 1 is the id of leader
        Expect(AppendEntriesResult(2, success = true)), // leader is set
        Tell(Command("x", 1)), // reuse leader ref as client ref
        Tell(Command("y", 2)),
        FishForMsg { case _: Command => true },
        FishForMsg { case _: Command => true }
      )
      .run()
  }

  it should "respond command received at term = 0 (right after init) when it becomes leader" in {
    val checker = new FollowerEndPointChecker()
    checker
      .setElectionTime(1 seconds) // keep server in initialized follower state longer
      .setActions(
        Tell(Command("x", 1)), // reuse probe as client
        Tell(Command("y", 2)), // reuse probe as client
        Expect(RequestVote(1, checker.getId, 0, 0)),
        Reply(RequestVoteResult(1, success = true)),
        FishForMsg { case CommandResponse(true, _) => true },
        FishForMsg { case CommandResponse(true, _) => true }
      )
      .run()
  }

  it should "return server status after receiving GetStatus request" in {
    val checker = new FollowerEndPointChecker()
    val term = 10
    val leaderId = 1
    checker
      .setActions(
        Tell(AppendEntries(term, leaderId, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(term, success = true)),
        Tell(GetStatus),
        Expect(Status(checker.getId, term, State.Follower, Some(leaderId)))
      )
      .run()
  }

  it should "never receive heartbeat from another leader" in {
    val term = 10
    val leaderId = 1
    new FollowerEndPointChecker()
      .setActions(
        Tell(AppendEntries(term, leaderId, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(term, success = true)),
        Tell(AppendEntries(term, leaderId + 1, 0, 0, Seq[LogEntry](), 0)),
        FishForMsg { case _: LeaderNotUniqueException => true }
      )
      .run()
  }

  "Candidate" should "relaunch RequestVote every election time" in {
    val checker = new CandidateEndPointChecker()
    checker
      .setActions(
        Within(150 millis, 200 millis, Expect(RequestVote(2, checker.getId, 0, 0))),
        Within(150 millis, 200 millis, Expect(RequestVote(3, checker.getId, 0, 0))),
        Within(150 millis, 200 millis, Expect(RequestVote(4, checker.getId, 0, 0)))
      )
      .run()
  }

  it should "respond commands received right after becoming candidate " +
    "when it finally become leader" in {
    new CandidateEndPointChecker()
      .setActions(
        Tell(Command("x", 1)),
        Tell(Command("y", 2)),
        Reply(RequestVoteResult(1, success = true)),
        FishForMsg { case CommandResponse(true, _) => true },
        FishForMsg { case CommandResponse(true, _) => true }
      )
      .run()
  }

  it should "start a new term if no one wins the election" in { // 1 server vs 1 probe
    val checker = new CandidateEndPointChecker()
    checker
      .setActions(
        Reply(RequestVoteResult(1, success = false)),
        Expect(RequestVote(2, checker.getId, 0, 0))
      )
      .run()
  }

  it should "become leader when received messages of majority" in {
    val checker = new CandidateEndPointChecker()
    checker
      .setProbeNum(5)
      .setActions(
        MajorReply(RequestVoteResult(1, success = true)),
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0))
      )
      .run()
  }

  it should "launch election of the next term when only minority granted" in {
    val checker = new CandidateEndPointChecker()
    checker
      .setProbeNum(5)
      .setActions(
        MinorReply(
          RequestVoteResult(1, success = true),
          Some(RequestVoteResult(1, success = false))
        ),
        Expect(RequestVote(2, checker.getId, 0, 0))
      )
      .run()
  }

  it should "become follower when one of the received term in RequestVoteResult is larger than " +
    "current term" in {
    val checker = new CandidateEndPointChecker()
    checker
      .setProbeNum(5)
      .setActions(
        Reply(RequestVoteResult(2, success = true)),
        Expect(RequestVote(3, checker.getId, 0, 0))
      )
      .run()
  }

  it should "become follower if it receives a RequestVote with term larger than " +
    "its current term" in {
    new CandidateEndPointChecker()
      .setActions(
        Tell(RequestVote(2, 0, 0, 0)),
        Expect(RequestVoteResult(2, success = true)),
        Tell(AppendEntries(2, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(2, success = true))
      )
      .run()
  }

  it should "become follower if it receives a AppendEntries with term larger than " +
    "its current term" in {
    new CandidateEndPointChecker()
      .setActions(
        Tell(AppendEntries(2, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(2, success = true)),
        Tell(AppendEntries(2, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(2, success = true))
      )
      .run()
  }

  it should "become follower when received term in AppendEntriesResult equal to his own" in {
    val checker = new CandidateEndPointChecker()
    checker
      .setActions(
        Tell(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(1, success = true)),
        Expect(RequestVote(2, checker.getId, 0, 0))
      )
      .run()
  }

  it should "reject AppendEntries if its term is smaller than current term" in {
    new CandidateEndPointChecker()
      .setProbeNum(5)
      .setActions(
        Tell(AppendEntries(0, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(1, success = false))
      )
      .run()
  }

  it should "return server status after receive GetStatus request af" in {
    val checker = new CandidateEndPointChecker()
    val term = 10
    val leaderId = 1
    checker
      .setActions(
        Tell(AppendEntries(term, leaderId, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(term, success = true)),
        Tell(GetStatus),
        Expect(Status(checker.getId, term, State.Follower, Some(leaderId)))
      )
      .run()
  }

  "leader" should "send heartbeat to every follower every heartbeat interval" in {
    val tickTime = 100.millis
    val checker = new LeaderEndPointChecker()
    checker
      .setActions(
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
        Rep(
          3,
          Within(
            tickTime,
            tickTime * 2,
            Reply(AppendEntriesResult(1, success = true)),
            Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0))
          )
        )
      )
      .run()
  }

  it should "become follower if it receives a RequestVote with term larger than " +
    "its current term" in {
    new LeaderEndPointChecker()
      .setActions(
        Tell(RequestVote(10, 0, 0, 0)),
        Expect(RequestVoteResult(10, success = true)),
        Tell(AppendEntries(10, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(10, success = true))
      )
      .run()
  }

  it should "become follower if the received term of AppendEntriesResult is larger than " +
    "current term" in {
    val checker = new LeaderEndPointChecker()
    checker
      .setProbeNum(5)
      .setActions(
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
        Reply(AppendEntriesResult(2, success = true)),
        Expect(RequestVote(3, checker.getId, 0, 0))
      )
      .run()
  }

  it should "continue to distribute heartbeat when AppendEntry requests are rejected" in {
    val checker = new LeaderEndPointChecker()
    checker
      .setProbeNum(5)
      .setActions(
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
        MinorReply(
          AppendEntriesResult(1, success = false),
          Some(AppendEntriesResult(1, success = true))
        ),
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
        MinorReply(
          AppendEntriesResult(1, success = true),
          Some(AppendEntriesResult(1, success = false))
        ),
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
        Reply(AppendEntriesResult(1, success = true))
      )
      .run()
  }

  it should "continue to distribute heartbeat when some heartbeat acks are not received" in {
    val checker = new LeaderEndPointChecker()
    checker
      .setProbeNum(5)
      .setActions(
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
        MinorReply(AppendEntriesResult(1, success = false)),
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
        MajorReply(AppendEntriesResult(1, success = true)),
        Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
        Reply(AppendEntriesResult(1, success = true))
      )
      .run()
  }

  it should "return server status after receive GetStatus request af" in {
    val checker = new LeaderEndPointChecker()
    val term = 10
    val leaderId = 1
    checker
      .setActions(
        Tell(AppendEntries(term, leaderId, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(term, success = true)),
        Tell(GetStatus),
        Expect(Status(checker.getId, term, State.Follower, Some(leaderId)))
      )
      .run()
  }

  it should "never receive an AppendEntries RPC with the same term" in {
    val checker = new LeaderEndPointChecker()
    checker
      .setActions(
        Expect(AppendEntries(1, checker.getId, 0, 0, List(), 0)),
        Reply(AppendEntriesResult(1, success = true)),
        Tell(AppendEntries(1, 2, 0, 0, Seq[LogEntry](), 0)),
        FishForMsg { case _: LeaderNotUniqueException => true }
      )
      .run()
  }

  //TODO: add more exception test
  it should "asdf" in {
    val res = ("raft" + "akka")
    println(res)
  }

}
