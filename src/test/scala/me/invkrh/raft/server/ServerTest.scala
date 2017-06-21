package me.invkrh.raft.server

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ActorSystem, PoisonPill}

import me.invkrh.raft.exception.{HeartbeatIntervalException, InvalidLeaderException}
import me.invkrh.raft.kit.{RaftTestHarness, _}
import me.invkrh.raft.message._

class ServerTest extends RaftTestHarness("SeverSpec") { self =>

  /////////////////////////////////////////////////
  //  Leader Election
  /////////////////////////////////////////////////

  "Server" should {
    "throw exception when election time is shorter than heartbeat interval" in {
      intercept[HeartbeatIntervalException] {
        val server =
          system.actorOf(Server.props(0, 100 millis, 100 millis, 150 millis), "svr")
        server ! PoisonPill
      }
    }

    "start if none of the bootstrap members are resolved" in {
      val server =
        system.actorOf(Server.props(0, 150 millis, 150 millis, 100 millis))
      expectNoMsg()
      server ! PoisonPill
    }

    "stop the system if ShutDown message is received" in {
      val system = ActorSystem("StopTest")
      val server =
        system.actorOf(Server.props(0, 150 millis, 150 millis, 100 millis))
      server ! ShutDown
      assertResult(true) {
        Thread.sleep(5000)
        system.whenTerminated.isCompleted
      }
    }
  }

  "Follower" should {
    "accept AppendEntries when the term of the message is equal to his own" in {
      new FollowerEndPointChecker()
        .setActions(
          Tell(AppendEntries(0, 0, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(0, success = true))
        )
        .run()
    }

    "reject AppendEntries when the term of the message is smaller than his own" in {
      new FollowerEndPointChecker()
        .setActions(
          Tell(AppendEntries(-1, 0, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(0, success = false))
        )
        .run()
    }

    "reply AppendEntries with larger term which is received with the message" in {
      new FollowerEndPointChecker()
        .setActions(
          Tell(AppendEntries(2, 0, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(2, success = true))
        )
        .run()
    }

    "reject RequestVote when the term of the message is equal to his own" in {
      new FollowerEndPointChecker()
        .setActions(Tell(RequestVote(0, 0, 0, 0)), Expect(RequestVoteResult(0, success = false)))
        .run()
    }

    "reject RequestVote when the term of the message is smaller than his own" in {
      new FollowerEndPointChecker()
        .setActions(Tell(RequestVote(-1, 0, 0, 0)), Expect(RequestVoteResult(0, success = false)))
        .run()
    }

    "accept RequestVote when the term of the message is (at least) larger than his own" in {
      new FollowerEndPointChecker()
        .setActions(Tell(RequestVote(10, 0, 0, 0)), Expect(RequestVoteResult(10, success = true)))
        .run()
    }

    "reject RequestVote when it has already voted" in {
      new FollowerEndPointChecker()
        .setActions(
          Tell(RequestVote(1, 0, 0, 0)),
          Expect(RequestVoteResult(1, success = true)),
          Reply(RequestVote(1, 1, 0, 0)),
          Expect(RequestVoteResult(1, success = false))
        )
        .run()
    }

    "launch election after election timeout elapsed" in {
      val checker = new FollowerEndPointChecker()
      checker
        .setActions(Expect(RequestVote(1, checker.getId, 0, 0)))
        .run()
    }

    "reset election timeout if AppendEntries msg is received" in {
      val electionTime = 1000.millis
      val tickTime = 200.millis
      val heartbeatNum = 8
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

    "resend command to leader if leader is elected" in {
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

    "respond command received at term = 0 (right after init) when it becomes leader" in {
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

    "return server status after receiving GetStatus request" in {
      val checker = new FollowerEndPointChecker()
      val term = 10
      val leaderId = 1
      checker
        .setActions(
          Tell(AppendEntries(term, leaderId, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(term, success = true)),
          Tell(GetStatus),
          Expect(Status(checker.getId, term, ServerState.Follower, Some(leaderId)))
        )
        .run()
    }

    "never receive heartbeat from another leader" in {
      val term = 10
      val leaderId = 1
      new FollowerEndPointChecker()
        .setActions(
          Tell(AppendEntries(term, leaderId, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(term, success = true)),
          Tell(AppendEntries(term, leaderId + 1, 0, 0, Seq[LogEntry](), 0)),
          FishForMsg { case _: InvalidLeaderException => true }
        )
        .run()
    }
  }

  "Candidate" should {

    "memorize leaderID after becoming following and receiving heartbeat" in {
      val leaderId = 20
      val term = 10
      val checker = new CandidateEndPointChecker()
      checker
        .setActions(
          Tell(RequestVote(term, leaderId, 0, 0)),
          Expect(RequestVoteResult(term, success = true)),
          Tell(GetStatus),
          Expect(Status(checker.getId, term, ServerState.Follower, None)),
          Tell(AppendEntries(term, leaderId, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(term, success = true)),
          Tell(GetStatus),
          Expect(Status(checker.getId, term, ServerState.Follower, Some(leaderId)))
        )
        .run()
    }

    "relaunch RequestVote every election time" in {
      val electionTimeout = 1.second
      val checker = new CandidateEndPointChecker()
        .setElectionTime(electionTimeout)
      checker
        .setActions(
          Within(electionTimeout, electionTimeout, Expect(RequestVote(2, checker.getId, 0, 0))),
          Within(electionTimeout, electionTimeout, Expect(RequestVote(3, checker.getId, 0, 0))),
          Within(electionTimeout, electionTimeout, Expect(RequestVote(4, checker.getId, 0, 0)))
        )
        .run()
    }

    "respond commands received right after becoming candidate " +
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

    "start a new term if no one wins the election" in { // 1 server vs 1 probe
      val checker = new CandidateEndPointChecker()
      checker
        .setActions(
          Reply(RequestVoteResult(1, success = false)),
          Expect(RequestVote(2, checker.getId, 0, 0))
        )
        .run()
    }

    "become leader when received messages of majority" in {
      val checker = new CandidateEndPointChecker()
      checker
        .setProbeNum(5)
        .setActions(
          MajorReply(RequestVoteResult(1, success = true)),
          Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0))
        )
        .run()
    }

    "launch election of the next term when only minority granted" in {
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

    "become follower when one of the received term in RequestVoteResult is larger than " +
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

    "become follower if it receives a RequestVote with term larger than " +
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

    "become follower if it receives a AppendEntries with term larger than " +
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

    "become follower when received term in AppendEntriesResult equal to his own" in {
      val checker = new CandidateEndPointChecker()
      checker
        .setActions(
          Tell(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(1, success = true)),
          Expect(RequestVote(2, checker.getId, 0, 0))
        )
        .run()
    }

    "reject AppendEntries if its term is smaller than current term" in {
      new CandidateEndPointChecker()
        .setProbeNum(5)
        .setActions(
          Tell(AppendEntries(0, 0, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(1, success = false))
        )
        .run()
    }

    "return server status after receive GetStatus request" in {
      val checker = new CandidateEndPointChecker()
      val term = 10
      val leaderId = 1
      checker
        .setActions(
          Tell(AppendEntries(term, leaderId, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(term, success = true)),
          Tell(GetStatus),
          Expect(Status(checker.getId, term, ServerState.Follower, Some(leaderId)))
        )
        .run()
    }
  }

  "leader" should {
    "send heartbeat to every follower every heartbeat interval" in {
      val tickTime = 200.millis
      val checker = new LeaderEndPointChecker()
      checker
        .setTickTime(tickTime)
        .setElectionTime(tickTime * 2)
        .setActions(
          Rep(
            3,
            Within(
              tickTime,
              tickTime * 2,
              Reply(AppendEntriesResult(1, success = true)),
              Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0)),
              Reply(AppendEntriesResult(1, success = true)),
              Expect(AppendEntries(1, checker.getId, 0, 0, Seq[LogEntry](), 0))
            )
          )
        )
        .run()
    }

    "become follower if it receives a RequestVote with term larger than " +
      "its current term" in {
      val term = 10
      new LeaderEndPointChecker()
        .setActions(
          Tell(RequestVote(term, 0, 0, 0)),
          Expect(RequestVoteResult(term, success = true)),
          Tell(AppendEntries(term, 0, 0, 0, Seq[LogEntry](), 0)),
          Expect(AppendEntriesResult(term, success = true))
        )
        .run()
    }

    "become follower if the received term of AppendEntriesResult is larger than " +
      "current term" in {
      val checker = new LeaderEndPointChecker()
      checker
        .setProbeNum(5)
        .setActions(
          Reply(AppendEntriesResult(2, success = true)),
          Expect(RequestVote(3, checker.getId, 0, 0))
        )
        .run()
    }

    "continue to distribute heartbeat when AppendEntry requests are rejected" in {
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

    "continue to distribute heartbeat when some heartbeat acks are not received" in {
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

    "return server status after receive GetStatus request" in {
      val checker = new LeaderEndPointChecker()
      checker
        .setActions(
          Tell(GetStatus),
          Expect(Status(checker.getId, 1, ServerState.Leader, Some(checker.getId)))
        )
        .run()
    }

    "never receive an AppendEntries RPC with the same term" in {
      new LeaderEndPointChecker()
        .setActions(
          Reply(AppendEntriesResult(1, success = true)),
          Tell(AppendEntries(1, 2, 0, 0, Seq[LogEntry](), 0)),
          FishForMsg { case _: InvalidLeaderException => true }
        )
        .run()
    }
  }

}
