package me.invkrh.raft.deploy.server

import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.pipe
import me.invkrh.raft.server.Message.{GetStatus, Init, Status}
import me.invkrh.raft.server.State
import me.invkrh.raft.util.Logging

class InitialStatusChecker(serverPaths: Seq[String]) extends Actor with Logging {
  import context.dispatcher
  var memberDict: Map[Int, ActorRef] = Map()

  logInfo("Resolving members ...")
  for (path <- serverPaths) {
    context.actorSelection(path).resolveOne(5.seconds) pipeTo self
  }

  private val timeout = context.system.scheduler.scheduleOnce(10.seconds, self, ResolutionTimeout)

  override def receive: Receive = {
    case server: ActorRef =>
      server ! GetStatus
    case Status(id, 0, State.Bootstrap, None) => // check initial states of all concerned members
      memberDict.get(id) match {
        case Some(_) => throw new RuntimeException(s"Server $id already exists")
        case None =>
          memberDict = memberDict.updated(id, sender())
          logInfo(
            s"(${memberDict.size}/${serverPaths.size}) " +
              s"Found server $id at ${sender().path}"
          )
          if (memberDict.size == serverPaths.size) {
            timeout.cancel()
            logInfo("All the members are found. Cluster is up. Wait for leader election...")
            memberDict foreach {
              case (_, ref) => ref ! Init(memberDict)
            }
            context.system.terminate()
          }
      }
    case ResolutionTimeout =>
      logError("Bootstrap server resolution failed. Shutting down the system...")
      context.system.terminate()
  }
}

object InitialStatusChecker {
  def props(serverPaths: Seq[String]) = Props(new InitialStatusChecker(serverPaths))
}
