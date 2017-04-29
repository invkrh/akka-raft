package me.invkrh.raft

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.pattern.{AskTimeoutException, gracefulStop}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import me.invkrh.raft.core.Exception.{EmptyInitMemberException, HeartbeatIntervalException}
import me.invkrh.raft.core.Message._
import me.invkrh.raft.core.Server
import me.invkrh.raft.util.{Metric, UID}
import org.scalatest._

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

  def stopServer(serverRef: ActorRef): Unit = {
    try {
      val stopped: Future[Boolean] = gracefulStop(serverRef, 5 seconds)
      Await.result(stopped, 6 seconds)
    } catch {
      case e: AskTimeoutException ⇒ throw e
    }
  }

  trait ProbeAction
  // expect no messages
  case class ExpNoMsg() extends ProbeAction
  // expect message
  case class Expect(message: RaftMessage) extends ProbeAction
  // tell message
  case class Tell(message: RaftMessage) extends ProbeAction
  // reply message for ask pattern
  case class Reply(message: RaftMessage) extends ProbeAction
  // action happens after sleep time
  case class Delay(sleep: FiniteDuration, action: ProbeAction) extends ProbeAction
  // majority of the probes reply
  case class MajorReply(message: RaftMessage, msgForOthers: Option[RaftMessage] = None) extends ProbeAction
  // minority of the probes reply
  case class MinorReply(message: RaftMessage, msgForOthers: Option[RaftMessage] = None) extends ProbeAction
  // action finishes within the duration
  case class Within(min: FiniteDuration, max: FiniteDuration, actions: ProbeAction*)
      extends ProbeAction
  // action repeats itself
  case class Rep(times: Int, actions: ProbeAction*) extends ProbeAction

  trait EndpointChecker {
    private var probeNum: Int = 1
    private var actions: List[ProbeAction] = List()
    private var id = 0
    private var electionTime: FiniteDuration = 150 millis
    private var tickTime: FiniteDuration = 100 millis

    protected def clusterSetup(): Unit

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

    def setActions(actions: ProbeAction*): this.type = {
      this.actions = actions.toList
      this
    }

    val name: String = s"srv-$id-${UID()}" // use random name
    lazy val probes: Array[TestProbe] = Array.fill(probeNum)(TestProbe("follower"))
    lazy val server: ActorRef = Server.run(
      id,
      electionTime,
      electionTime,
      tickTime,
      probes.zipWithIndex.map {
        case (probe, index) =>
          (index + 1, probe.ref.path.toString)
      }.toMap updated (0, s"akka://SeverSpec/user/$name"),
      name
    )

    def checkActions(actions: Seq[ProbeAction]): Unit = {
      actions foreach {
        case ExpNoMsg() => probes foreach (_ expectNoMsg)
        case Delay(sleep, action) =>
          Thread.sleep(sleep.toMillis)
          checkActions(Seq(action))
        case Expect(msg) =>
          probes foreach (_.expectMsg(electionTime + tickTime, msg))
        case Within(min, max, actions @ _ *) =>
          // loose time range in case time assertion is not accurate
          val minMS = min.toMillis
          val maxMS = max.toMillis
          within(minMS * 0.9 millis, maxMS * 1.1 millis) {
            checkActions(actions)
          }
        case Tell(msg) =>
          // it does not work with ask pattern in which the sender is not server,
          // but a actor under /temp path
          probes foreach (p => server.tell(msg, p.ref))
        case Reply(msg) =>
          // if msg is from ask pattern, reply method must be used,
          // or there will be some AskTimeoutException
          probes foreach (_ reply msg)
        case MajorReply(msg, optMsg) =>
          val maj = probes.length / 2 + 1
          probes.take(maj) foreach (_ reply msg)
          optMsg match {
            case Some(other) => probes.drop(maj) foreach (_ reply other)
            case None =>
          }
        case MinorReply(msg, optMsg) =>
          val min = probes.length / 2 - 1
          probes.take(min) foreach (_ reply msg)
          optMsg match {
            case Some(other) => probes.drop(min) foreach (_ reply other)
            case None =>
          }
        case Rep(times, actions @ _ *) =>
          (0 until times).foreach { _ =>
            checkActions(actions)
          }
      }
    }

    def run(): Unit = {
      // enforce lazy val
      info("Checking " + this.server.path)
      clusterSetup()
      checkActions(actions)
      probes foreach (_.ref ! PoisonPill)
      stopServer(server)
    }
  }

  class FollowerEndPointChecker() extends EndpointChecker {
    override def clusterSetup(): Unit = {}
  }

  class CandidateEndPointChecker() extends EndpointChecker {
    override def clusterSetup(): Unit = {
      probes.foreach(_ expectMsg RequestVote(1, 0, 0, 0))
    }
  }

  class LeaderEndPointChecker() extends EndpointChecker {
    override def clusterSetup(): Unit = {
      probes foreach { p =>
        p expectMsg RequestVote(1, 0, 0, 0)
        p.reply(RequestVoteResult(1, success = true))
      }
    }
  }

  /////////////////////////////////////////////////
  //  Leader Election
  /////////////////////////////////////////////////

  "Server" should "throw exception when member dict is empty" in {
    intercept[EmptyInitMemberException] {
      val server = system.actorOf(Server.props(0, 150 millis, 150 millis, 100 millis, Map()))
      server ! PoisonPill
    }
  }

  it should "throw exception when election time is shorter than heartbeat interval" in {
    intercept[HeartbeatIntervalException] {
      val server =
        system.actorOf(Server.props(0, 100 millis, 100 millis, 150 millis, Map(0 -> "svr")), "svr")
      server ! PoisonPill
    }
  }

  it should "start if none of the bootstrap members are resolved" in {
    val server =
      system.actorOf(Server.props(0, 150 millis, 150 millis, 100 millis, Map(0 -> "abc")))
    expectNoMsg()
    server ! PoisonPill
  }

  "Follower" should "return current term and success flag when AppendEntries is received" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(AppendEntries(0, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(0, success = true))
      )
      .run()
  }

  it should "resend commands received when leader was not elected" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(Command("x", 1)),
        Tell(AppendEntries(0, 1, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(0, success = true)),
        Expect(Command("x", 1))
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

  it should "reject RequestVote when the term of the message is smaller than his own" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(RequestVote(-1, 0, 0, 0)),
        Expect(RequestVoteResult(0, success = false))
      )
      .run()
  }

  it should "reply RequestVote with (at least )larger term which is received with the message" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(RequestVote(0, 0, 0, 0)),
        Expect(RequestVoteResult(0, success = true))
      )
      .run()
    new FollowerEndPointChecker()
      .setActions(
        Tell(RequestVote(1, 0, 0, 0)),
        Expect(RequestVoteResult(1, success = true))
      )
      .run()
  }

  it should "reject RequestVote when it has already voted" in {
    new FollowerEndPointChecker()
      .setActions(
        Tell(RequestVote(0, 0, 0, 0)),
        Expect(RequestVoteResult(0, success = true)),
        Reply(RequestVote(0, 1, 0, 0)),
        Expect(RequestVoteResult(0, success = false))
      )
      .run()
  }

  it should "launch election after election timeout elapsed" in {
    new FollowerEndPointChecker()
      .setActions(Expect(RequestVote(1, 0, 0, 0)))
      .run()
  }

  it should "reset election timeout if AppendEntries msg is received" in {
    val electionTime = 150.millis
    val tickTime = 100.millis
    val heartbeatNum = 3
    new FollowerEndPointChecker()
      .setElectionTime(electionTime)
      .setTickTime(tickTime)
      .setActions(
        Within(
          tickTime * heartbeatNum + electionTime,
          tickTime * heartbeatNum + electionTime * 2,
          Rep(heartbeatNum,
              Delay(tickTime, Tell(AppendEntries(0, 0, 0, 0, Seq[LogEntry](), 0))),
              Expect(AppendEntriesResult(0, success = true))),
          Expect(RequestVote(1, 0, 0, 0))
        )
      )
      .run()
  }

  "Candidate" should "relaunch RequestVote every election time" in {
    new CandidateEndPointChecker()
      .setActions(
        Within(150 millis, 200 millis, Expect(RequestVote(2, 0, 0, 0))),
        Within(150 millis, 200 millis, Expect(RequestVote(3, 0, 0, 0))),
        Within(150 millis, 200 millis, Expect(RequestVote(4, 0, 0, 0)))
      )
      .run()
  }

  it should "resend cached client messages when it becomes leader" in {
    new CandidateEndPointChecker()
      .setActions(
        Tell(Command("x", 1)),
        Tell(Command("y", 2)),
        Reply(RequestVoteResult(1, success = true)),
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(CommandAccepted()),
        Expect(CommandAccepted())
      )
      .run()
  }

  it should "resend cached client messages when it go back to follower" in {
    new CandidateEndPointChecker()
      .setActions(
        Tell(Command("x", 1)),
        Tell(Command("y", 2)),
        Tell(AppendEntries(2, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(2, success = true)),
        Tell(AppendEntries(2, 0, 0, 0, Seq[LogEntry](), 0)), // enforce resending cached message
        Expect(AppendEntriesResult(2, success = true)),
        Expect(RequestVote(3, 0, 0, 0)),
        Reply(RequestVoteResult(3, success = true)),
        Expect(AppendEntries(3, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(CommandAccepted()),
        Expect(CommandAccepted())
      )
      .run()
  }

  it should "become leader when received messages of majority" in {
    new CandidateEndPointChecker()
      .setProbeNum(5)
      .setActions(
        MajorReply(RequestVoteResult(1, success = true)),
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0))
      )
      .run()
  }

  it should "launch election of the next term when only minority granted" in {
    new CandidateEndPointChecker()
      .setProbeNum(5)
      .setActions(
        MinorReply(RequestVoteResult(1, success = true),
                   Some(RequestVoteResult(1, success = false))),
        Expect(RequestVote(2, 0, 0, 0))
      )
      .run()
  }

  it should "become follower when the received term in RequestVoteResult is larger than " +
    "current term" in {
    new CandidateEndPointChecker()
      .setProbeNum(5)
      .setActions(
        Reply(RequestVoteResult(2, success = true)),
        Expect(RequestVote(3, 0, 0, 0))
      )
      .run()
  }

  it should "become follower when received term in AppendEntriesResult is larger than " +
    "current term" in {
    new CandidateEndPointChecker()
      .setProbeNum(5)
      .setActions(
        Tell(AppendEntries(2, 0, 0, 0, Seq[LogEntry](), 0)),
        Expect(AppendEntriesResult(2, success = true)),
        Expect(RequestVote(3, 0, 0, 0))
      )
      .run()
  }

  "Leader" should "send heartbeat to every follower every heartbeat interval" in {
    val tickTime = 100.millis
    new LeaderEndPointChecker()
      .setActions(
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        Rep(3,
            Within(tickTime,
                   tickTime * 2,
                   Reply(AppendEntriesResult(1, success = true)),
                   Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0))))
      )
      .run()
  }

  it should "become follower if the received term of AppendEntriesResult is larger than " +
    "current term" in {
    new LeaderEndPointChecker()
      .setProbeNum(5)
      .setActions(
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        Reply(AppendEntriesResult(2, success = true)),
        Expect(RequestVote(3, 0, 0, 0))
      )
      .run()
  }

  it should "continue to distribute heartbeat when AppendEntry requests are rejected" in {
    new LeaderEndPointChecker()
      .setProbeNum(5)
      .setActions(
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        MinorReply(AppendEntriesResult(1, success = false),
                   Some(AppendEntriesResult(1, success = true))),
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        MinorReply(AppendEntriesResult(1, success = true),
                   Some(AppendEntriesResult(1, success = false))),
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        Reply(AppendEntriesResult(1, success = true))
      )
      .run()
  }

  it should "continue to distribute heartbeat when some heartbeat acks are not received" in {
    new LeaderEndPointChecker()
      .setProbeNum(5)
      .setActions(
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        MinorReply(AppendEntriesResult(1, success = false)),
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        MajorReply(AppendEntriesResult(1, success = true)),
        Expect(AppendEntries(1, 0, 0, 0, Seq[LogEntry](), 0)),
        Reply(AppendEntriesResult(1, success = true))
      )
      .run()
  }

}
