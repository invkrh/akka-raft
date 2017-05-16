package me.invkrh.raft.deploy.cluster

import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.pipe
import me.invkrh.raft.server.Message.{GetStatus, Init, Status}
import me.invkrh.raft.server.State
import me.invkrh.raft.util.Logging

class Initiator(serverPaths: Seq[String]) extends Actor with Logging {
  case object ResolutionTimeout

  import context.dispatcher
  var memberDict: Map[Int, ActorRef] = Map()

  logInfo("Resolving members ...")
  for (path <- serverPaths) {
    context.actorSelection(path).resolveOne(5.seconds) pipeTo self
  }

  private val timeout = context.system.scheduler.scheduleOnce(10.seconds, self, ResolutionTimeout)

  override def receive: Receive = {
    case server: ActorRef => server ! GetStatus
    case Status(id, 0, State.Bootstrap, None) => // check initial states of all concerned members
      memberDict = memberDict + (id -> sender())
      logInfo(s"(${memberDict.size}/${serverPaths.size}) Found server $id at ${sender().path}")
      if (memberDict.size == serverPaths.size) {
        timeout.cancel()
        logInfo("All the members are found. Cluster is up. Wait for leader election...")
        memberDict foreach {
          case (_, ref) => ref ! Init(memberDict)
        }
        context.system.terminate()
      }
    case ResolutionTimeout =>
      logError("Bootstrap server resolution failed. Shutting down the system...")
      context.system.terminate()
  }
}

object Initiator {
  def props(serverPaths: Seq[String]) = Props(new Initiator(serverPaths))
}
