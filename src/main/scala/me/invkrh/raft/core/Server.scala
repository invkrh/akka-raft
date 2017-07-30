package me.invkrh.raft.core

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Scheduler}
import akka.pattern.pipe

import me.invkrh.raft.deploy.raftServerName
import me.invkrh.raft.exception._
import me.invkrh.raft.message._
import me.invkrh.raft.util._

object Server {

  def checkOrThrow(assert: Boolean, cause: Throwable): Unit = {
    if (!assert) throw cause
  }

  def props(
    id: Int,
    minElectionTime: FiniteDuration,
    maxElectionTime: FiniteDuration,
    tickTime: FiniteDuration
  ): Props = {
    checkOrThrow(minElectionTime > tickTime, HeartbeatIntervalException())
    Props(new Server(id, minElectionTime, maxElectionTime, tickTime))
  }

  def props(id: Int, conf: ServerConf): Props = {
    props(id, conf.minElectionTime, conf.maxElectionTime, conf.tickTime)
  }

  def run(
    id: Int,
    minElectionTime: FiniteDuration,
    maxElectionTime: FiniteDuration,
    tickTime: FiniteDuration,
    name: String
  )(implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(id, minElectionTime, maxElectionTime, tickTime), name)
  }

  def run(id: Int, serverConf: ServerConf)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(id, serverConf), s"$raftServerName-$id")
  }
}

class Server(
  val id: Int,
  minElectionTime: FiniteDuration,
  maxElectionTime: FiniteDuration,
  tickTime: FiniteDuration
) extends Actor
    with Logging {

  import context._

  import Server.checkOrThrow

  implicit val scheduler: Scheduler = system.scheduler

  // Persistent state on all servers
  private var curTerm = 0
  private var votedFor: Option[Int] = None
  private var logs: Vector[LogEntry] = Vector(LogEntry(0, Void))

  // Volatile state on all servers
  @volatile private var commitIndex = 0
  @volatile private var lastApplied = 0

  // Volatile state on leaders
  @volatile private var nextIndex = Map[Int, Int]()
  @volatile private var matchIndex = Map[Int, Int]()

  // Additional state
  private implicit var members: Map[Int, ActorRef] = Map()
  private var curState: ServerState.Value = ServerState.Bootstrap
  private var curLeaderId: Option[Int] = None

  val clientMessageCache = new MessageCache[Command](id)
  val electionTimer: RandomizedTimer =
    new RandomizedTimer(minElectionTime, maxElectionTime, StartElection).setTarget(self)
  val heartBeatTimer: PeriodicTimer = new PeriodicTimer(tickTime, Tick).setTarget(self)

  override def loggingPrefix: String = s"[$id-$curState]"

  override def preStart(): Unit = {}

  override def postStop(): Unit = {
    logInfo(s"Server $id stops and cancel all timer scheduled tasks")
    electionTimer.stop()
    heartBeatTimer.stop()
  }

  def approveAppendEntries(request: AppendEntries): Unit = {}

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Processing batch of approved responses with Majority
  //////////////////////////////////////////////////////////////////////////////////////////////////

  def processConversation(conversation: RequestVoteConversation): Unit = {
    var maxTerm = -1
    var validateCnt = 1 // validated by itself
    conversation.content foreach {
      case Exchange(request: RequestVote, response, follId, follRef) =>
        response match {
          case RequestVoteResult(term, false) if term > curTerm =>
            logInfo(
              s"[Follwer ID = $follId] New leader is detected by receiving $response " +
                s"(source address: ${follRef.path})"
            )
            maxTerm = Math.max(term, maxTerm)
          case RequestVoteResult(term, false) if term == curTerm =>
            logInfo(
              s"[Follwer ID = $follId] rejected RequestVote request at term ${request.term} " +
                s"(source address: ${follRef.path})"
            )
          case RequestVoteResult(term, true) if term == curTerm =>
            logInfo(
              s"[Follwer ID = $follId] voted for server $id " + s"(source address: ${follRef.path})"
            )
            validateCnt += 1
          case RequestTimeout(term) =>
            logInfo(
              s"[Follwer ID = $follId] Request RequestVote time out at $term" +
                s" (source address: ${follRef.path})"
            )
          case _ => throw invalidResponseException(response, curTerm)
        }
    }

    if (maxTerm > curTerm) {
      becomeFollower(maxTerm)
    } else {
      if (validateCnt > members.size / 2) {
        logInfo(
          s"Election for term $curTerm is ended since majority is reached " +
            s"($validateCnt / ${members.size})"
        )
        becomeLeader()
      } else {
        logInfo(s"Majority is not reached ($validateCnt / ${members.size})")
      }
    }
    logDebug(s"=== end of processing conversation ===")
  }

  def processConversation(conversation: AppendEntriesConversation): Unit = {
    var maxTerm = -1
    var validateCnt = 1 // validated by itself
    conversation.content foreach {
      case Exchange(request: AppendEntries, response, follId, follRef) =>
        response match {
          case AppendEntriesResult(term, false) if term > curTerm =>
            logInfo(
              s"[Follwer ID = $follId] New leader is detected by receiving $response " +
                s" (source address: ${follRef.path})"
            )
            maxTerm = Math.max(term, maxTerm)
          case AppendEntriesResult(term, false) if term == curTerm =>
            nextIndex.updated(follId, nextIndex(follId) - 1)
          case AppendEntriesResult(term, true) if term == curTerm =>
            validateCnt += 1
            matchIndex.updated(follId, request.prevLogIndex + request.entries.size)
          case RequestTimeout(term) =>
            logInfo(
              s"[Follwer ID = $follId] Request AppendEntries time out at $term" +
                s" (source address: ${follRef.path})"
            )
          case _ => throw invalidResponseException(response, curTerm)
        }
    }

    if (maxTerm > curTerm) {
      becomeFollower(maxTerm)
    } else {
      if (validateCnt > members.size / 2) {
        logDebug(
          s"Heartbeat for term $curTerm is ended since majority is reached " +
            s"($validateCnt / ${members.size}), committing logs"
        )
        // TODO: apply to state machine
      } else {
        logInfo(s"Majority is not reached ($validateCnt / ${members.size})")
      }
    }
    logDebug(s"=== end of processing conversation ===")
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // End Point
  //////////////////////////////////////////////////////////////////////////////////////////////////

  def adminEndpoint: Receive = {
    case MembershipRequest => sender ! Membership(members)
    case GetStatus => sender ! Status(id, curTerm, curState, curLeaderId)
    // TODO: Add more admin endpoint
  }

  def startElectionEndpoint: Receive = {
    case StartElection => becomeCandidate()
  }

  def tickEndPoint: Receive = {
    case Tick =>
      LeaderMessageHub(curTerm, id, commitIndex, nextIndex, logs)
        .distributeRPCRequest(tickTime)
        .map(AppendEntriesConversation) pipeTo self
  }

  def appendEntriesEndPoint: Receive = {
    case request: AppendEntries =>
      if (curTerm > request.term) {
        sender ! AppendEntriesResult(curTerm, success = false)
      } else if (curTerm == request.term) {
        curState match {
          case ServerState.Leader =>
            throw MultiLeaderException(id, request.leaderId, request.term)
          case ServerState.Candidate =>
            // TODO: Add precessing
            sender ! AppendEntriesResult(request.term, success = true)
            becomeFollower(request.term, request.leaderId)
          case ServerState.Follower =>
            logDebug(s"Heartbeat from leader ${request.leaderId} at term ${request.term}")
            curLeaderId foreach { leaderId =>
              checkOrThrow(
                leaderId == request.leaderId,
                MultiLeaderException(id, request.leaderId, request.term)
              )
            }
            curLeaderId = Some(request.leaderId)
            // TODO: refactor this function, at least:
            clientMessageCache.flushTo(members(request.leaderId))
            // TODO: Add precessing
            sender ! AppendEntriesResult(request.term, success = true)
            electionTimer.restart()
        }
      } else {
        // TODO: Add precessing
        sender ! AppendEntriesResult(request.term, success = true)
        becomeFollower(request.term, request.leaderId)
      }
  }

  def requestVoteEndPoint: Receive = {
    case request: RequestVote =>
      if (curTerm > request.term) {
        sender ! RequestVoteResult(curTerm, voteGranted = false)
      } else {
        if (curTerm < request.term) {
          becomeFollower(request.term)
        }
        val isUpToDate: Boolean =
          if (request.lastLogTerm > logs.last.term) {
            true
          } else if (request.lastLogTerm == logs.last.term) {
            request.lastLogIndex >= logs.size - 1
          } else {
            false
          }
//        logInfo("test = " + (votedFor.isEmpty, votedFor.contains(request.candidateId), isUpToDate))
        if ((votedFor.isEmpty || votedFor.get == request.candidateId) && isUpToDate) {
          votedFor = Some(request.candidateId)
          sender ! RequestVoteResult(request.term, voteGranted = true)
          electionTimer.restart()
        } else {
          sender ! RequestVoteResult(request.term, voteGranted = false)
        }
      }
  }

  def commandEndPoint: Receive = {
    case cmd: Command =>
      curState match {
        case ServerState.Leader =>
          logInfo("Client command received: " + cmd)
          logs = logs :+ LogEntry(curTerm, cmd)
          sender() ! CommandResponse(success = true)
        case ServerState.Candidate =>
          curLeaderId match {
            case Some(sid) => // unreachable check, just in case
              throw CandidateHasLeaderException(sid)
            case None => clientMessageCache.add(sender, cmd)
          }
        case ServerState.Follower =>
          curLeaderId match {
            case Some(leaderId) =>
              val leaderRef =
                members.getOrElse(leaderId, throw new Exception("Unknown leader id: " + leaderId))
              leaderRef forward cmd
            case None => clientMessageCache.add(sender, cmd)
          }
      }
  }

  def conversationEndPoint: Receive = {
    case c: AppendEntriesConversation => processConversation(c)
    case c: RequestVoteConversation => processConversation(c)
  }

  def irrelevantMsgEndPoint: Receive = {
    case msg: RaftMessage =>
      logWarn(s"Irrelevant messages found: $msg, from ${sender.path}")
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Server state conversion
  //////////////////////////////////////////////////////////////////////////////////////////////////

  // TODO: Member Management problem
  override def receive: Receive = // Initial state
    adminEndpoint orElse {
      case Membership(index) =>
        if (index.nonEmpty) {
          logInfo(s"Server $id initialized")
          members = index
          members foreach {
            case (svrId, _) =>
              nextIndex = nextIndex.updated(svrId, 1)
              matchIndex = matchIndex.updated(svrId, 0)
          }
          becomeFollower(0)
        } else {
          throw EmptyMembershipException()
        }
    }

  def follower: Receive =
    commandEndPoint orElse
      startElectionEndpoint orElse
      appendEntriesEndPoint orElse
      requestVoteEndPoint orElse
      adminEndpoint orElse
      irrelevantMsgEndPoint

  def candidate: Receive =
    conversationEndPoint orElse
      commandEndPoint orElse
      startElectionEndpoint orElse
      appendEntriesEndPoint orElse
      requestVoteEndPoint orElse
      adminEndpoint orElse
      irrelevantMsgEndPoint

  def leader: Receive =
    conversationEndPoint orElse
      commandEndPoint orElse
      tickEndPoint orElse
      appendEntriesEndPoint orElse
      requestVoteEndPoint orElse
      adminEndpoint orElse
      irrelevantMsgEndPoint

  def becomeFollower(newTerm: Int, newLeader: Int = -1): Unit = {
    curTerm = newTerm
    curState = ServerState.Follower
    votedFor = None

    if (newLeader == -1) { // New leader is unknown
      if (curTerm == 0) {
        logInfo(s"At term $curTerm, start up as follower")
      } else {
        logInfo(s"At term $curTerm, unknown new leader detected")
      }
      curLeaderId = None
    } else { // New leader id is given
      logInfo(s"At term $curTerm, new leader $newLeader detected")
      curLeaderId = Some(newLeader)
      for {
        leaderId <- curLeaderId
        leaderRef <- members.get(leaderId)
      } yield {
        clientMessageCache.flushTo(leaderRef)
      }
    }
    become(follower)
    heartBeatTimer.stop()
    electionTimer.restart()
  }

  def becomeCandidate(): Unit = {
    curTerm = curTerm + 1
    curState = ServerState.Candidate
    votedFor = Some(id)
    curLeaderId = None
    logInfo(s"Election for term $curTerm started, server $id becomes candidate")
    become(candidate)
    CandidateMessageHub(curTerm, id, logs)
      .distributeRPCRequest(minElectionTime)
      .map(RequestVoteConversation.apply) pipeTo self
    electionTimer.restart()
  }

  def becomeLeader(): Unit = {
    // term not changed
    curState = ServerState.Leader
    curLeaderId = Some(id)
    votedFor = None
    logInfo(s"Server $id becomes leader")
    become(leader)
    clientMessageCache.flushTo(self)
    electionTimer.stop()
    heartBeatTimer.restart()
  }
}
