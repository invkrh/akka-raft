package me.invkrh.raft.core

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{FiniteDuration, _}

import akka.actor.{ActorRef, Scheduler}
import akka.pattern.{after, ask, AskTimeoutException}
import akka.util.Timeout

import me.invkrh.raft.message.ClientMessage._
import me.invkrh.raft.message.RPCMessage._
import me.invkrh.raft.util.Logging

sealed trait MessageHub extends Logging {
  def term: Int
  def selfId: Int
  def logs: List[LogEntry]
  def request(followerId: Int): RPCRequest
  def members: Map[Int, ActorRef]

  implicit def executor: ExecutionContext
  implicit def scheduler: Scheduler

  def retry[T](ft: => Future[T], delay: FiniteDuration, retries: Int): Future[T] = {
    ft recoverWith {
      case e if retries > 0 =>
        logWarn(s"Retrying [$retries] on error: $e")
        after(delay, scheduler)(retry(ft, delay, retries - 1))
    }
  }

  def distributeRPCRequest(
      maxResponseTime: FiniteDuration,
      retries: Int = 1): Future[Iterable[Exchange]] = {
    // tighten timeout duration
    implicit val timeout: Timeout =
      Timeout((maxResponseTime.toMillis / (retries + 1) * 0.8).toLong, MILLISECONDS)
    val exchanges = for {
      (followId, ref) <- members.par if followId != selfId
    } yield {
      val req = request(followId)
      retry(ref ? req, Duration.Zero, retries) map {
        case res: RPCResponse => Exchange(req, res, followId)
      } recover {
        case _: AskTimeoutException => Exchange(req, RequestTimeout(term), followId)
      }
    }
    Future.sequence(exchanges.seq)
  }
}

case class CandidateMessageHub(
    term: Int,
    selfId: Int,
    logs: List[LogEntry],
    members: Map[Int, ActorRef])(implicit val scheduler: Scheduler, val executor: ExecutionContext)
  extends MessageHub {
  def request(followerId: Int): RPCRequest = {
    RequestVote(
      term = term,
      candidateId = selfId,
      lastLogIndex = logs.size - 1,
      lastLogTerm = logs.last.term)
  }
}

case class LeaderMessageHub(
    term: Int,
    selfId: Int,
    commitIndex: Int,
    nextIndex: Map[Int, Int],
    logs: List[LogEntry],
    members: Map[Int, ActorRef])(implicit val scheduler: Scheduler, val executor: ExecutionContext)
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
      leaderCommit = commitIndex)
  }
}
