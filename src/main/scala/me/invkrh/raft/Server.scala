package me.invkrh.raft

import java.util.UUID

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{Actor, ActorRef, Props, Scheduler}
import akka.event.Logging
import Config._
import RPCMessage.{AppendEntries, AppendEntriesResult, RequestVote}
import me.invkrh.raft.util.{Logging, TimeOut}

object Server {
  def props(electionTimeout: FiniteDuration, members: Seq[ActorRef]) =
    Props(new Server(electionTimeout, members))

  def props(members: Seq[ActorRef]): Props =
    props(ELECTION_TIMEOUT_DURATION seconds, members)
}

class Server(electionTimeout: FiniteDuration, members: Seq[ActorRef]) extends Actor with Logging {

  implicit val scheduler: Scheduler = context.system.scheduler

  val electionTimeOut = new TimeOut(electionTimeout, startElection())

  val id: String = UUID.randomUUID().toString
  var curTerm = 0
  var state = State.Follower

  def startElection(): Boolean = {
    info("Election is started")
    members foreach (_ ! RequestVote(curTerm + 1, id, 0, 0))
    true
  }

  def isTermOutdated(term: Int): Boolean =
    if (curTerm < term) {
      if (state != State.Follower) { // either Leader or Candidate
        state = State.Follower
      }
      curTerm = term
      true
    } else {
      false
    }

  override def preStart(): Unit = {
    info(s"Start as $state, with election timeout $electionTimeout")
    electionTimeOut.reset()
  }

  override def receive: Receive = {
    case msg: AppendEntries =>
      electionTimeOut.reset()
      sender ! AppendEntriesResult(curTerm, success = true)
  }
}
