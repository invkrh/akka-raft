package me.invkrh.raft.kit

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

import akka.actor.SupervisorStrategy._
import akka.actor.{Actor, ActorRef, ActorSystem, OneForOneStrategy, PoisonPill, Props}
import akka.pattern.{AskTimeoutException, gracefulStop}
import akka.testkit.TestProbe
import me.invkrh.raft.core.Message.{Init, RequestVote, RequestVoteResult}
import me.invkrh.raft.core.Server
import me.invkrh.raft.util.UID

sealed trait EndpointChecker {

  implicit val system: ActorSystem
  protected var probeNum: Int = 1
  protected var actions: List[ProbeCheck] = List()
  protected var id = 42
  protected var electionTime: FiniteDuration = 150 millis
  protected var tickTime: FiniteDuration = 100 millis

  protected def clusterSetup(): Unit

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

  val probes: Array[TestProbe] = Array.fill(probeNum)(TestProbe("follower"))
  val supervisor: ActorRef =
    system.actorOf(
      Props(new ServerSupervisor(s"svr-$id", probes.map(_.ref): _*)),
      s"supervisor-${UID()}"
    )
  val server: ActorRef = {
    val pb = TestProbe()
    supervisor.tell(Server.props(id, electionTime, electionTime, tickTime), pb.ref)
    pb.expectMsgType[ActorRef]
  }

  def stopServer(serverRef: ActorRef): Unit = {
    try {
      val stopped: Future[Boolean] = gracefulStop(serverRef, 5 seconds)
      Await.result(stopped, 6 seconds)
    } catch {
      case e: AskTimeoutException ⇒ throw e
    }
  }

  def run(): Unit = {
    val dict = probes.zipWithIndex.map {
      case (p, i) => (i + 1, p.ref)
    }.toMap updated (id, server)
    server ! Init(dict)
    clusterSetup()
    actions foreach { _.execute(server, probes) }
    probes foreach (_.ref ! PoisonPill)
    stopServer(supervisor) // server is a child of supervisor
  }
}

class ServerSupervisor(serverName: String, probes: ActorRef*) extends Actor {
  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case thr: Throwable =>
      probes foreach { _ ! thr }
      Restart // or make it configurable/controllable during the test
  }
  def receive: PartialFunction[Any, Unit] = {
    case p: Props => sender ! context.actorOf(p, serverName)
  }
}

class FollowerEndPointChecker(implicit val system: ActorSystem) extends EndpointChecker {
  override def clusterSetup(): Unit = {}
}

class CandidateEndPointChecker(implicit val system: ActorSystem) extends EndpointChecker {
  override def clusterSetup(): Unit = {
    probes.foreach(_ expectMsg RequestVote(1, id, 0, 0))
  }
}

class LeaderEndPointChecker(implicit val system: ActorSystem) extends EndpointChecker {
  override def clusterSetup(): Unit = {
    probes foreach { p =>
      p expectMsg RequestVote(1, id, 0, 0)
      p.reply(RequestVoteResult(1, success = true))
    }
  }
}
