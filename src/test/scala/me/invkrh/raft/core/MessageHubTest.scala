package me.invkrh.raft.core

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.testkit.TestProbe

import me.invkrh.raft.kit.RaftTestHarness
import me.invkrh.raft.message.RPCMessage._

class MessageHubTest extends RaftTestHarness("MessageHubTestSystem") {

  "CandidateMessageHub" should {
    val term = 1
    val logIndex = 20
    val candidateId = 42
    val followerId = 1
    val logs = genDummyLogsUntilNewTerm(term, logIndex)
    val request = RequestVote(term, candidateId, logIndex, term)
    val response = RequestVoteResult(term, success = true)
    val probe = TestProbe()
    val membership = Map(followerId -> probe.ref)

    "exchange messages with other members" in {
      val ft =
        CandidateMessageHub(term, candidateId, logs, membership).distributeRPCRequest(5 seconds)
      probe.expectMsg(request)
      probe.reply(response)
      assertResult(Iterable(Exchange(request, response, followerId))) {
        Await.result(ft, 10 seconds)
      }
    }
  }

  "LeaderMessageHub" should {
    val term = 1
    val logIndex = 20
    val leaderId = 42
    val followerId = 1
    val leaderCommitIndex = 9
    val followerNextIndex = 15
    val nextIndex = Map(followerId -> followerNextIndex)
    val logs = genDummyLogsUntilNewTerm(term, logIndex)
    val request = AppendEntries(
      term,
      leaderId,
      followerNextIndex - 1,
      term - 1,
      logs.slice(followerNextIndex, logIndex + 1),
      leaderCommitIndex
    )
    val response = AppendEntriesResult(term, success = true)
    val probe = TestProbe()
    val membership = Map(followerId -> probe.ref)

    "exchange messages with other members" in {
      val ft = LeaderMessageHub(term, leaderId, leaderCommitIndex, nextIndex, logs, membership)
        .distributeRPCRequest(5 seconds)
      probe.expectMsg(request)
      probe.reply(response)
      assertResult(Iterable(Exchange(request, response, followerId))) {
        Await.result(ft, 10 seconds)
      }
    }
  }
}
