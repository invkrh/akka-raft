package me.invkrh.raft.kit

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.{gracefulStop, AskTimeoutException}
import akka.testkit.TestProbe

import me.invkrh.raft.core.Server
import me.invkrh.raft.message.AdminMessage.Membership
import me.invkrh.raft.message.ClientMessage.LogEntry
import me.invkrh.raft.message.RPCMessage._
import me.invkrh.raft.storage.MemoryStore
import me.invkrh.raft.util.UID

sealed trait EndpointChecker {

  implicit val system: ActorSystem
  protected var probeNum: Int = 1
  protected var actions: List[ProbeCheck] = List()
  protected var id = 42
  protected var electionTime: FiniteDuration = 150 millis
  protected var tickTime: FiniteDuration = 100 millis

  protected def preActions(): Unit

  def getId: Int = id

  def setId(id: Int): this.type = {
    this.id = id
    this
  }

  def setElectionTime(electionTime: FiniteDuration): this.type = {
    this.electionTime = electionTime
    this
  }

  def setTickTime(tickTime: FiniteDuration): this.type = {
    this.tickTime = tickTime
    this
  }

  def setProbeNum(probeNum: Int): this.type = {
    this.probeNum = probeNum
    this
  }

  def setActions(actions: ProbeCheck*): this.type = {
    this.actions = actions.toList
    this
  }

  def stopServer(serverRef: ActorRef): Unit = {
    try {
      val stopped: Future[Boolean] = gracefulStop(serverRef, 5 seconds)
      Await.result(stopped, 6 seconds)
    } catch {
      case e: AskTimeoutException => throw e
    }
  }

  val memoryStore: MemoryStore = MemoryStore()

  def run(): Unit = {

    val probes: List[TestProbe] = List.fill(probeNum)(TestProbe("raft-probe"))
    val supervisor: ActorRef =
      system.actorOf(
        Props(new ExceptionDetector(s"svr-$id", probes.map(_.ref): _*)),
        s"supervisor-${UID()}")
    val server: ActorRef = {
      val pb = TestProbe()
      supervisor.tell(
        Server.props(id, electionTime, electionTime, tickTime, 0, memoryStore),
        pb.ref)
      pb.expectMsgType[ActorRef]
    }
    val dict = probes.zipWithIndex.map {
      case (p, i) => (i + 1, p.ref)
    }.toMap updated (id, server)
    server ! Membership(dict)
    preActions()
    actions foreach { _.execute(server, probes) }
    probes foreach (_.ref ! PoisonPill)
    stopServer(supervisor) // server is a child of supervisor
  }
}

class FollowerEndPointChecker(implicit val system: ActorSystem) extends EndpointChecker {
  override protected def preActions(): Unit = {}
}

class CandidateEndPointChecker(implicit val system: ActorSystem) extends EndpointChecker {
  override protected def preActions(): Unit = {
    actions = Expect(RequestVote(1, id, 0, 0)) :: actions
  }
}

class LeaderEndPointChecker(implicit val system: ActorSystem) extends EndpointChecker {
  override protected def preActions(): Unit = {
    actions = Expect(RequestVote(1, id, 0, 0)) ::
      Reply(RequestVoteResult(1, success = true)) ::
      Expect(AppendEntries(1, id, 0, 0, List[LogEntry](), 0)) :: actions
  }
}
