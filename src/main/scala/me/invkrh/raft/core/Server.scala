package me.invkrh.raft.core

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Scheduler}
import akka.pattern.pipe

import me.invkrh.raft.deploy.raftServerName
import me.invkrh.raft.exception._
import me.invkrh.raft.message._
import me.invkrh.raft.message.AdminMessage._
import me.invkrh.raft.message.ClientMessage._
import me.invkrh.raft.message.RPCMessage._
import me.invkrh.raft.message.TimerMessage._
import me.invkrh.raft.storage.DataStore
import me.invkrh.raft.util._

// TODO: reduce resource usage, cpu and network

object Server {
  def props(
      id: Int,
      minElectionTime: FiniteDuration,
      maxElectionTime: FiniteDuration,
      tickTime: FiniteDuration,
      rcpRetries: Int,
      dataStore: DataStore): Props = {
    if (minElectionTime <= tickTime) {
      throw HeartbeatIntervalException()
    }
    Props(new Server(id, minElectionTime, maxElectionTime, tickTime, rcpRetries, dataStore))
  }

  def props(id: Int, conf: ServerConf): Props = {
    props(
      id,
      conf.minElectionTime,
      conf.maxElectionTime,
      conf.tickTime,
      conf.rpcRetries,
      conf.dataStore)
  }

  def run(
      id: Int,
      minElectionTime: FiniteDuration,
      maxElectionTime: FiniteDuration,
      tickTime: FiniteDuration,
      rpcRetries: Int,
      dataStore: DataStore,
      name: String)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(
      props(id, minElectionTime, maxElectionTime, tickTime, rpcRetries, dataStore),
      name)
  }

  def run(id: Int, serverConf: ServerConf)(implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(id, serverConf), s"$raftServerName-$id")
  }

  def syncLogsFromLeader(request: AppendEntries, logs: List[LogEntry]): (List[LogEntry], Int) = {
    def logMerge(requestLogs: List[LogEntry], localLogs: List[LogEntry]): List[LogEntry] = {
      (requestLogs, localLogs) match {
        case (x +: xs, y +: ys) =>
          if (x.term == y.term) {
            if (x.command != y.command) {
              throw LogMatchingPropertyException(x.command, y.command)
            }
            x +: logMerge(xs, ys)
          } else {
            requestLogs
          }
        case (Nil, _) => localLogs
        case (_, Nil) => requestLogs
      }
    }
    val (before, after) = logs.splitAt(request.prevLogIndex + 1)
    val lastNewEntryIndex = request.entries.size + request.prevLogIndex
    val mergedLogs = before ++ logMerge(request.entries, after)
    (mergedLogs, lastNewEntryIndex)
  }

  def findNewCommitIndex(
      commitIndex: Int,
      matchIndexValue: Iterable[Int],
      logs: List[LogEntry],
      curTerm: Int): Option[Int] = {
    val maj = matchIndexValue.size / 2 + 1 // self in included
    val eligible = matchIndexValue.filter(_ > commitIndex).toList.sortBy(-_).lift(maj - 1)
    eligible.filter(e => logs(e).term == curTerm)
  }
}

class Server(
    val id: Int,
    minElectionTime: FiniteDuration,
    maxElectionTime: FiniteDuration,
    tickTime: FiniteDuration,
    rcpRetries: Int,
    dataStore: DataStore)
  extends Actor
  with Logging {

  // implicit context variable
  // there exists an implicit ActorRef for this actor: self
  implicit val executor: ExecutionContextExecutor = context.dispatcher
  implicit val scheduler: Scheduler = context.system.scheduler

  // Persistent state on all servers
  private var curTerm = 0
  private var votedFor: Option[Int] = None
  private var logs: List[LogEntry] = List(LogEntry(0, Init, null))

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

  private val dataStoreManager = context.actorOf(DataStoreManager.props(dataStore), "dsm")
  private val clientMessageCache = new MessageCache[Command](id)
  private val electionTimer = new RandomizedTimer(minElectionTime, maxElectionTime, StartElection)
  private val heartBeatTimer = new PeriodicTimer(tickTime, Tick)

  override def loggingPrefix: String = s"[$id-$curState]"

  override def preStart(): Unit = {}

  override def postStop(): Unit = {
    logInfo(s"Server $id stops and cancel all timer scheduled tasks")
    electionTimer.stop()
    heartBeatTimer.stop()
  }

  def requestSetCommitIndex(commitIndex: Int): Unit = {
    this.commitIndex = commitIndex
    if (commitIndex > lastApplied) {
      logDebug("Applying command to state machine")
      logDebug("dataStoreManager: " + dataStoreManager)
      dataStoreManager !
        ApplyLogsRequest(
          logs.slice(lastApplied + 1, commitIndex + 1), // end is exclusive
          commitIndex)
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Processing batch of approved responses with Majority
  //////////////////////////////////////////////////////////////////////////////////////////////////

  def processConversation(cvs: Conversation): Unit = {
    var maxTerm = -1
    val validateExchange = new ArrayBuffer[Exchange]()
    cvs.content foreach {
      case ex @ Exchange(request, response, fid) =>
        val ref = members(fid)
        response match {
          // Reply to leader
          case AppendEntriesResult(term, false) if term == curTerm =>
            logDebug(s"AppendEntries is failed on server $fid at term $term (address: $ref)")
            nextIndex = nextIndex.updated(fid, Math.max(1, nextIndex(fid) - 1)) // at least 1
          case AppendEntriesResult(term, true) if term == curTerm =>
            logDebug(s"AppendEntries is succeeded on server $fid at term $term (address: $ref)")
            val req = request.asInstanceOf[AppendEntries]
            val matchIndexValue = req.prevLogIndex + req.entries.size
            matchIndex = matchIndex.updated(fid, matchIndexValue)
            nextIndex = nextIndex.updated(fid, matchIndexValue + 1)
            validateExchange.append(ex)

          // Reply to candidate
          case RequestVoteResult(term, false) if term == curTerm =>
            logDebug(s"Vote rejected by server $fid at term $term (address: $ref)")
          case RequestVoteResult(term, true) if term == curTerm =>
            logDebug(s"Vote granted by server $fid at term $term (address: $ref)")
            validateExchange.append(ex)

          // Timeout Response to both of candidate and leader
          case _: RequestTimeout =>
            logInfo(s"Request $request is time out when connecting server $fid (address: $ref)")
          case _: RPCResponse =>
            if (response.term > curTerm && !response.success) {
              logInfo(s"Higher term is detected by $response from server $fid (address: $ref)")
              maxTerm = Math.max(response.term, maxTerm)
            } else {
              throw InvalidResponseException(response, curTerm)
            }
        }
    }

    if (maxTerm > curTerm) {
      becomeFollower(maxTerm)
    } else {
      val validateCnt = validateExchange.size + 1
      val hint = s"$validateCnt / ${members.size}"
      if (validateCnt > members.size / 2) {
        logInfo(s"Majority is reached ($hint) at term $curTerm")
        cvs match {
          case _: AppendEntriesConversation =>
            logInfo("Applying logs on leader")
            Server
              .findNewCommitIndex(commitIndex, matchIndex.values, logs, curTerm)
              .foreach(requestSetCommitIndex)
          case _: RequestVoteConversation =>
            becomeLeader()
        }
      } else {
        logInfo(s"Majority is NOT reached ($hint) at term $curTerm")
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // End Point
  //////////////////////////////////////////////////////////////////////////////////////////////////

  def adminEndpoint: Receive = {
    case GetStatus =>
      sender ! Status(
        id,
        curTerm,
        curState,
        curLeaderId,
        nextIndex,
        matchIndex,
        commitIndex,
        lastApplied)
  }

  def startElectionEndpoint: Receive = {
    case StartElection => becomeCandidate()
  }

  def tickEndPoint: Receive = {
    case Tick =>
      LeaderMessageHub(curTerm, id, commitIndex, nextIndex, logs, members)
        .distributeRPCRequest(tickTime, rcpRetries)
        .map(AppendEntriesConversation) pipeTo self
  }

  def appendEntriesEndPoint: Receive = {
    case request: AppendEntries =>
      if (request.term < curTerm) {
        sender ! AppendEntriesResult(curTerm, success = false)
      } else {
        // Same term with different leader
        if (request.term == curTerm && curLeaderId.exists(_ != request.leaderId)) {
          throw MultiLeaderException(id, request.leaderId, request.term)
        }
        becomeFollower(request.term, Some(request.leaderId))
        if (logs.size - 1 >= request.prevLogIndex &&
          logs(request.prevLogIndex).term == request.prevLogTerm) {
          val (mergedLogs, lastNewEntryIndex) = Server.syncLogsFromLeader(request, logs)
          logs = mergedLogs
          if (request.leaderCommit > commitIndex) {
            logDebug("Updating local commit index")
            requestSetCommitIndex(Math.min(request.leaderCommit, lastNewEntryIndex))
          }
          sender ! AppendEntriesResult(curTerm, success = true)
        } else {
          sender ! AppendEntriesResult(curTerm, success = false)
        }
      }
  }

  def requestVoteEndPoint: Receive = {
    case request: RequestVote =>
      if (curTerm > request.term) {
        sender ! RequestVoteResult(curTerm, success = false)
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
        if ((votedFor.isEmpty || votedFor.get == request.candidateId) && isUpToDate) {
          votedFor = Some(request.candidateId)
          sender ! RequestVoteResult(request.term, success = true)
          electionTimer.restart()
        } else {
          sender ! RequestVoteResult(request.term, success = false)
        }
      }
  }

  def commandEndPoint: Receive = {
    case cmd: Command =>
      curLeaderId match {
        case Some(leaderId) =>
          if (leaderId == id) { // if this server is leader
            logDebug("Leader received cmd: " + cmd)
            logs = logs :+ LogEntry(curTerm, cmd, sender)
          } else {
            val leaderRef =
              members.getOrElse(leaderId, throw new Exception("Unknown leader id: " + leaderId))
            leaderRef forward cmd
          }
        case None => clientMessageCache.add(sender, cmd)
      }
  }

  def conversationEndPoint: Receive = {
    case c: Conversation => processConversation(c)
  }

  def commitIndexACKEndPoint: Receive = {
    case CommandApplied(n) =>
      logDebug("command applied")
      this.lastApplied = n
  }

  def irrelevantMsgEndPoint: Receive = {
    case msg: RaftMessage =>
      logWarn(s"Irrelevant messages found: $msg, from ${sender.path}")
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Server state conversion
  //////////////////////////////////////////////////////////////////////////////////////////////////

  override def receive: Receive = // Initial state
    adminEndpoint orElse {
      case Membership(index) =>
        if (index.nonEmpty) {
          logInfo(s"Server $id initialized")
          members = index
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
      commitIndexACKEndPoint orElse
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
      commitIndexACKEndPoint orElse
      irrelevantMsgEndPoint

  def becomeFollower(newTerm: Int, newLeader: Option[Int] = None): Unit = {
    curTerm = newTerm
    votedFor = None

    newLeader match {
      case Some(newLeaderId) =>
        if (!curLeaderId.contains(newLeaderId)) { // new leader is not current leader
          logInfo(s"At term $curTerm, new leader (id = $newLeaderId) detected, become follower")
          curLeaderId = newLeader
        }
        members.get(newLeaderId) foreach clientMessageCache.flushTo
      case None =>
        if (curTerm == 0) {
          logInfo(s"At term $curTerm, start up as follower")
        } else {
          logInfo(s"At term $curTerm, request with higher term detected, become follower")
        }
        curLeaderId = None
    }

    curState = ServerState.Follower
    context.become(follower)
    heartBeatTimer.stop()
    electionTimer.restart()
  }

  def becomeCandidate(): Unit = {
    curTerm = curTerm + 1
    votedFor = Some(id)
    curLeaderId = None
    logInfo(s"Election for term $curTerm started, server $id becomes candidate")
    curState = ServerState.Candidate
    context.become(candidate)
    CandidateMessageHub(curTerm, id, logs, members)
      .distributeRPCRequest(minElectionTime, rcpRetries)
      .map(RequestVoteConversation.apply) pipeTo self
    electionTimer.restart()
  }

  def becomeLeader(): Unit = {
    // term not changed
    curLeaderId = Some(id)
    votedFor = None
    // (re)initialize after election
    members foreach {
      case (svrId, _) =>
        nextIndex = nextIndex.updated(svrId, logs.size)
        matchIndex = matchIndex.updated(svrId, 0)
    }
    logInfo(s"Server $id becomes leader")
    curState = ServerState.Leader
    context.become(leader)
    clientMessageCache.flushTo(self)
    electionTimer.stop()
    heartBeatTimer.restart()
  }
}
