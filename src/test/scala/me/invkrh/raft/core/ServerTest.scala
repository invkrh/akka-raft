package me.invkrh.raft.core

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.PoisonPill
import akka.testkit.TestProbe

import me.invkrh.raft.exception._
import me.invkrh.raft.kit.{Tell, _}
import me.invkrh.raft.message.AdminMessage._
import me.invkrh.raft.message.ClientMessage._
import me.invkrh.raft.message.RPCMessage._
import me.invkrh.raft.storage.MemoryStore

class ServerTest extends RaftTestHarness("SeverSpec") { self =>

  def heartbeat(
    term: Int,
    leaderId: Int,
    prevLogIndex: Int = 0,
    prevLogTerm: Int = 0,
    leaderCommit: Int = 0
  ): AppendEntries =
    AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, Nil, leaderCommit)

  //////////////////////////////////////////////////////////////////////////////////////////////////
  //  Log Replication
  //////////////////////////////////////////////////////////////////////////////////////////////////

  "Log replication on leader side" should {
    "initialize nextIndex and matchIndex" in {
      new LeaderEndPointChecker()
        .setActions(
          Reply(AppendEntriesResult(1, success = true)),
          Tell(GetStatus),
          Expect(
            Status(
              42,
              1,
              ServerState.Leader,
              Some(42),
              Map(1 -> 1, 42 -> 1),
              Map(1 -> 0, 42 -> 0),
              0,
              0
            )
          )
        )
        .run()
    }

    "process message exchanges" in {
      new LeaderEndPointChecker()
        .setActions(
          Reply(AppendEntriesResult(1, success = true)),
          Rep(5, Tell(SET("x", 1))), // leader receives 5 commands
          FishForMsg { case req: AppendEntries if req.entries.size == 5 => true },
          Reply(AppendEntriesResult(1, success = true)),
          Expect(heartbeat(1, 42, 5, 1)),
          Tell(GetStatus),
          FishForMsg {
            case st: Status
                if st.nextIndex == Map(42 -> 1, 1 -> 6) && st.matchIndex == Map(42 -> 0, 1 -> 5) =>
              true
          },
          Expect(heartbeat(1, 42, 5, 1)),
          Reply(AppendEntriesResult(1, success = false)),
          FishForMsg {
            case req: AppendEntries if req.entries.size == 1 && req.prevLogIndex == 4 => true
          }
        )
        .run()
    }
  }

  "Log replication on follower side" should {

    def followerLogReplicationCheck(
      term: Int,
      prevIndex: Int,
      prevTerm: Int,
      leaderCommit: Int,
      isAccept: Boolean
    ): MemoryStore = {
      val checker = new FollowerEndPointChecker()
      val leaderId = 1
      checker
        .setActions(
          Tell(heartbeat(term, leaderId)),
          Expect(AppendEntriesResult(term, success = true)),
          Tell(
            AppendEntries(
              term,
              leaderId,
              prevIndex,
              prevTerm,
              List(dummyEntry(1, SET("x", 1))),
              leaderCommit
            )
          ),
          Expect(AppendEntriesResult(term, success = isAccept))
        )
        .run()
      checker.memoryStore
    }

    "accept AppendEntry request and apply command" in {
      val store = followerLogReplicationCheck(1, 0, 0, 1, isAccept = true)
      assertResult(Some(1)) {
        store.cache.get("x")
      }
    }

    "accept AppendEntry request but not apply command if leader commit is not bigger than " +
      "local commit index" in {
      val store = followerLogReplicationCheck(1, 0, 0, 0, isAccept = true)
      assertResult(None) {
        store.cache.get("x")
      }
    }

    "reject AppendEntry request if local logs are outdated" in {
      followerLogReplicationCheck(1, 1, 1, 0, isAccept = false)
    }

    "reject AppendEntry request if previous log term is not matched" in {
      followerLogReplicationCheck(1, 0, 2, 0, isAccept = false)
    }
  }

  "Server.findNewCommitIndex" should {

    "find right new commit index" in {
      assertResult(Some(31)) {
        Server.findNewCommitIndex(
          20,
          List(20, 30, 31, 32, 33, 34),
          genDummyLogsUntilNewTerm(4, 42),
          3
        )
      }
      assertResult(Some(31)) {
        Server.findNewCommitIndex(21, List(20, 23, 31, 32, 33), genDummyLogsUntilNewTerm(4, 42), 3)
      }
    }

    "return None if all matchIndex is smaller than commitIndex" in {
      assertResult(None) {
        Server.findNewCommitIndex(21, List(10, 13, 11, 12, 15), genDummyLogsUntilNewTerm(4, 42), 3)
      }
    }

    "return None if eligible value has a different term with current term" in {
      assertResult(None) {
        Server.findNewCommitIndex(21, List(20, 23, 31, 32, 33), genDummyLogsUntilNewTerm(3, 42), 4)
      }
    }
  }

  "Server.syncLogsByRequest" should {
    val probe = TestProbe()

    def req(prevIndex: Int, entries: LogEntry*): AppendEntries =
      AppendEntries(0, 0, prevIndex, 0, entries.toList, 0)

    val logs = List(
      dummyEntry(0, Init),
      dummyEntry(0, SET("x", 1)),
      dummyEntry(0, SET("x", 2)),
      dummyEntry(0, SET("x", 3)),
      dummyEntry(0, SET("x", 4)),
      dummyEntry(0, SET("x", 5))
    )

    "merge request logs and local logs" in {
      val request = req(3, dummyEntry(0, SET("x", 4)), dummyEntry(0, SET("x", 5)))
      assertResult((logs, 5)) {
        Server.syncLogsFromLeader(request, logs)
      }
    }

    "throw exception if log matching property is broken" in {
      val request = req(3, dummyEntry(0, SET("x", 1)), dummyEntry(0, SET("x", 2)))
      intercept[LogMatchingPropertyException] {
        Server.syncLogsFromLeader(request, logs)
      }
    }

    "return the index of the last new entry if local logs is longer than logs in the request" in {
      val request = req(2, dummyEntry(0, SET("x", 3)))
      assertResult((logs, 3)) {
        Server.syncLogsFromLeader(request, logs)
      }
    }

    "add new entries if prevIndex points to the last log" in {
      val request = req(5, dummyEntry(1, SET("x", 1)), dummyEntry(1, SET("x", 2)))
      val mergedLogs = logs ::: List(dummyEntry(1, SET("x", 1)), dummyEntry(1, SET("x", 2)))
      assertResult((mergedLogs, mergedLogs.size - 1)) {
        Server.syncLogsFromLeader(request, logs)
      }
    }

    "replace logs after inconsistent entry" in {
      val request = req(3, dummyEntry(1, SET("x", 1)), dummyEntry(1, SET("x", 2)))
      val mergedLogs = logs.take(4) ::: List(dummyEntry(1, SET("x", 1)), dummyEntry(1, SET("x", 2)))
      assertResult((mergedLogs, 5)) {
        Server.syncLogsFromLeader(request, logs)
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  //  Leader Election
  //////////////////////////////////////////////////////////////////////////////////////////////////

  "Server" should {

    "throw exception when election time is shorter than heartbeat interval" in {
      intercept[HeartbeatIntervalException] {
        val server =
          system.actorOf(Server.props(0, 100 millis, 100 millis, 150 millis, MemoryStore()), "svr")
        server ! PoisonPill
      }
    }

    "start if none of the bootstrap members are resolved" in {
      val server =
        system.actorOf(Server.props(0, 150 millis, 150 millis, 100 millis, new MemoryStore()))
      expectNoMsg()
      server ! PoisonPill
    }
  }

  "Follower" should {

    "resend command to leader if leader is elected" in {
      new FollowerEndPointChecker()
        .setActions(
          Tell(heartbeat(2, 1)), // 1 is the id of leader
          Expect(AppendEntriesResult(2, success = true)), // leader is set
          Tell(SET("x", 1)), // reuse leader ref as client ref
          Tell(SET("y", 2)),
          FishForMsg { case _: Command => true },
          FishForMsg { case _: Command => true }
        )
        .run()
    }

    "accept AppendEntries when the term of the message is equal to his own" in {
      new FollowerEndPointChecker()
        .setActions(Tell(heartbeat(0, 1)), Expect(AppendEntriesResult(0, success = true)))
        .run()
    }

    "reject AppendEntries when the term of the message is smaller than his own" in {
      new FollowerEndPointChecker()
        .setActions(Tell(heartbeat(-1, 0)), Expect(AppendEntriesResult(0, success = false)))
        .run()
    }

    "reply AppendEntries with larger term which is received with the message" in {
      new FollowerEndPointChecker()
        .setActions(Tell(heartbeat(2, 0)), Expect(AppendEntriesResult(2, success = true)))
        .run()
    }

    "accept the first RequestVote and reject the second one, " +
      "if two different candidates have the same term, retried messages " +
      " should also be accepted" in {
      new FollowerEndPointChecker()
        .setActions(
          Tell(RequestVote(1, 1, 0, 0)),
          Expect(RequestVoteResult(1, success = true)),
          Tell(RequestVote(1, 2, 0, 0)),
          Expect(RequestVoteResult(1, success = false)),
          Tell(RequestVote(1, 1, 0, 0)),
          Expect(RequestVoteResult(1, success = true))
        )
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

    "accept RequestVote after leader is elected " +
      "and a RequestVote is received with a higher term" in {
      new FollowerEndPointChecker()
        .setActions(
          Tell(RequestVote(1, 0, 0, 0)),
          Expect(RequestVoteResult(1, success = true)),
          Tell(RequestVote(10, 1, 0, 0)),
          Expect(RequestVoteResult(10, success = true))
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
              Delay(tickTime, Tell(heartbeat(0, 1))),
              Expect(AppendEntriesResult(0, success = true))
            ),
            Expect(RequestVote(1, checker.getId, 0, 0))
          )
        )
        .run()
    }

    "return server status after receiving GetStatus request" in {
      val checker = new FollowerEndPointChecker()
      val term = 10
      val leaderId = 1
      checker
        .setActions(
          Tell(heartbeat(term, leaderId)),
          Expect(AppendEntriesResult(term, success = true)),
          Tell(GetStatus),
          Expect(
            Status(checker.getId, term, ServerState.Follower, Some(leaderId), Map(), Map(), 0, 0)
          )
        )
        .run()
    }

    "never receive heartbeat from another leader" in {
      val term = 10
      val leaderId = 1
      new FollowerEndPointChecker()
        .setActions(
          Tell(heartbeat(term, leaderId)),
          Expect(AppendEntriesResult(term, success = true)),
          Tell(heartbeat(term, leaderId + 1)),
          FishForMsg { case _: MultiLeaderException => true }
        )
        .run()
    }
  }

  "Candidate" should {

    "memorize leaderID after becoming follower and receiving heartbeat" in {
      val leaderId = 1
      val higherTerm = 10
      val checker = new CandidateEndPointChecker()
      checker
        .setActions(
          Tell(RequestVote(higherTerm, leaderId, 0, 0)),
          Expect(RequestVoteResult(higherTerm, success = true)),
          Tell(GetStatus),
          Expect(Status(checker.getId, higherTerm, ServerState.Follower, None, Map(), Map(), 0, 0)),
          Tell(heartbeat(higherTerm, leaderId)),
          Expect(AppendEntriesResult(higherTerm, success = true)),
          Tell(GetStatus),
          Expect(
            Status(
              checker.getId,
              higherTerm,
              ServerState.Follower,
              Some(leaderId),
              Map(),
              Map(),
              0,
              0
            )
          )
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

    "start a new term if no one wins the election" in { // 1 server vs 1 probe
      val checker = new CandidateEndPointChecker()
      checker
        .setActions(
          Reply(RequestVoteResult(1, success = false)),
          Expect(RequestVote(2, checker.getId, 0, 0))
        )
        .run()
    }

    "accept VoteRequest with a higher termA after stepping down to follower" in {
      val checker = new CandidateEndPointChecker()
      checker
        .setActions(
          Reply(RequestVoteResult(2, success = false)), // step down
          Tell(RequestVote(10, 100, 0, 0)),
          Expect(RequestVoteResult(10, success = true))
        )
        .run()
    }

    "become leader when received messages of majority" in {
      val checker = new CandidateEndPointChecker()
      checker
        .setProbeNum(5)
        .setActions(
          MajorReply(RequestVoteResult(1, success = true)),
          Expect(heartbeat(1, checker.getId))
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
          Reply(RequestVoteResult(2, success = false)),
          Expect(RequestVote(3, checker.getId, 0, 0))
        )
        .run()
    }

    "become follower if it receives a RequestVote with term larger than its current term" in {
      new CandidateEndPointChecker()
        .setActions(
          Tell(RequestVote(2, 1, 0, 0)),
          Expect(RequestVoteResult(2, success = true)),
          Tell(heartbeat(2, 1)),
          Expect(AppendEntriesResult(2, success = true))
        )
        .run()
    }

    "become follower if it receives a AppendEntries with term larger than " +
      "its current term" in {
      new CandidateEndPointChecker()
        .setActions(
          Tell(heartbeat(2, 1)),
          Expect(AppendEntriesResult(2, success = true)),
          Tell(heartbeat(2, 1)),
          Expect(AppendEntriesResult(2, success = true))
        )
        .run()
    }

    "become follower when received term in AppendEntriesResult equal to his own" in {
      val checker = new CandidateEndPointChecker()
      checker
        .setActions(
          Tell(heartbeat(1, 0)),
          Expect(AppendEntriesResult(1, success = true)),
          Expect(RequestVote(2, checker.getId, 0, 0))
        )
        .run()
    }

    "reject AppendEntries if its term is smaller than current term" in {
      new CandidateEndPointChecker()
        .setProbeNum(5)
        .setActions(Tell(heartbeat(0, 0)), Expect(AppendEntriesResult(1, success = false)))
        .run()
    }

    "return server status after receive GetStatus request" in {
      val checker = new CandidateEndPointChecker()
      val term = 10
      val leaderId = 1
      checker
        .setActions(
          Tell(heartbeat(term, leaderId)),
          Expect(AppendEntriesResult(term, success = true)),
          Tell(GetStatus),
          Expect(
            Status(checker.getId, term, ServerState.Follower, Some(leaderId), Map(), Map(), 0, 0)
          )
        )
        .run()
    }
  }

  "leader" should {

    "never receive an AppendEntries RPC with the same term" in {
      new LeaderEndPointChecker()
        .setActions(
          Reply(AppendEntriesResult(1, success = true)),
          Tell(heartbeat(1, 2)),
          FishForMsg { case _: MultiLeaderException => true }
        )
        .run()
    }

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
              Expect(heartbeat(1, checker.getId)),
              Reply(AppendEntriesResult(1, success = true)),
              Expect(heartbeat(1, checker.getId))
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
          Tell(RequestVote(term, 1, 0, 0)),
          Expect(RequestVoteResult(term, success = true)),
          Tell(heartbeat(term, 1)),
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
          Reply(AppendEntriesResult(2, success = false)),
          Expect(RequestVote(3, checker.getId, 0, 0))
        )
        .run()
    }

    "continue to distribute heartbeat when AppendEntry requests are rejected" in {
      val checker = new LeaderEndPointChecker()
      checker
        .setProbeNum(5)
        .setActions(
          MajorReply(
            AppendEntriesResult(1, success = false),
            Some(AppendEntriesResult(1, success = true))
          ),
          Expect(heartbeat(1, checker.getId)),
          MajorReply(
            AppendEntriesResult(1, success = false),
            Some(AppendEntriesResult(1, success = true))
          ),
          Expect(heartbeat(1, checker.getId)),
          Reply(AppendEntriesResult(1, success = true))
        )
        .run()
    }

    "continue to distribute heartbeat when some heartbeat acks are not received" in {
      val checker = new LeaderEndPointChecker()
      checker
        .setProbeNum(5)
        .setActions(
          Expect(heartbeat(1, checker.getId)),
          MinorReply(AppendEntriesResult(1, success = false)),
          Expect(heartbeat(1, checker.getId)),
          MajorReply(AppendEntriesResult(1, success = true)),
          Expect(heartbeat(1, checker.getId)),
          Reply(AppendEntriesResult(1, success = true))
        )
        .run()
    }

    "return initialized leader status after receive GetStatus request" in {
      val checker = new LeaderEndPointChecker()
      checker
        .setActions(
          Tell(GetStatus),
          Expect(
            Status(
              checker.getId,
              1,
              ServerState.Leader,
              Some(checker.getId),
              Map(1 -> 1, checker.getId -> 1),
              Map(1 -> 0, checker.getId -> 0),
              0,
              0
            )
          )
        )
        .run()
    }
  }
}
