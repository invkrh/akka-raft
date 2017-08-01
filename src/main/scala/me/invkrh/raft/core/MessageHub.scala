package me.invkrh.raft.core

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

import akka.actor.{ActorRef, Scheduler}
import akka.pattern.{after, ask, AskTimeoutException}
import akka.util.Timeout

import me.invkrh.raft.message._
import me.invkrh.raft.util.Logging

sealed trait MessageHub extends Logging {
  def term: Int
  def selfId: Int
  def logs: List[LogEntry]
  def request(followerId: Int): RPCRequest
  def members: Map[Int, ActorRef]

  implicit def ec: ExecutionContext

  def retry[T](ft: Future[T], delay: FiniteDuration, retries: Int, retryMsg: String = "")(
    implicit scheduler: Scheduler
  ): Future[T] = {
    ft recoverWith {
      case e if retries > 0 =>
        logWarn(retryMsg + " current error: " + e)
        after(delay, scheduler)(retry(ft, delay, retries - 1, retryMsg))
    }
  }

  def distributeRPCRequest(timeoutDuration: FiniteDuration): Future[Iterable[Exchange]] = {
    // tighten timeout duration
    implicit val to = Timeout((timeoutDuration.toMillis * 0.8).toLong, timeoutDuration.unit)
    val exchanges = for {
      (followId, ref) <- members.par if followId != selfId
    } yield {
      val req = request(followId)
      (ref ? req) map {
        case res: RPCResponse => Exchange(req, res, followId)
      } recover {
        case _: AskTimeoutException =>
          Exchange(req, RequestTimeout(term), followId)
      }
    }
    Future.sequence(exchanges.seq)
  }
}

case class CandidateMessageHub(term: Int, selfId: Int, logs: List[LogEntry])(
  implicit val members: Map[Int, ActorRef],
  val ec: ExecutionContext
) extends MessageHub {
  def request(followerId: Int): RPCRequest = {
    RequestVote(
      term = term,
      candidateId = selfId,
      lastLogIndex = logs.size - 1,
      lastLogTerm = logs.last.term
    )
  }
}

case class LeaderMessageHub(
  term: Int,
  selfId: Int,
  commitIndex: Int,
  nextIndex: Map[Int, Int],
  logs: List[LogEntry]
)(implicit val members: Map[Int, ActorRef], val ec: ExecutionContext)
    extends MessageHub {
  def request(followerId: Int): RPCRequest = {
    val lastLogIndex: Int = logs.size - 1
    val followNextIndex = nextIndex(followerId)
    AppendEntries(
      term = term,
      leaderId = selfId,
      prevLogIndex = followNextIndex - 1,
      prevLogTerm = logs(followNextIndex - 1).term,
      entries =
        if (lastLogIndex >= followNextIndex) logs.drop(followNextIndex)
        else List[LogEntry](),
      leaderCommit = commitIndex
    )
  }
}
