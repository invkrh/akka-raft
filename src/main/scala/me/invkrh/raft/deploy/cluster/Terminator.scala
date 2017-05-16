package me.invkrh.raft.deploy.cluster

import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.pipe
import me.invkrh.raft.server.Message._
import me.invkrh.raft.util.Logging

class Terminator(serverPaths: Seq[String]) extends Actor with Logging {
  import context.dispatcher

  logInfo("Resolving members ...")
  for (path <- serverPaths) {
    context.actorSelection(path).resolveOne(5.seconds) pipeTo self
  }

  var serverCnt = 0
  override def receive: Receive = {
    case server: ActorRef =>
      serverCnt += 1
      server ! Shutdown
      if (serverCnt == serverPaths.size) {
        context.system.terminate()
      }
  }
}

object Terminator {
  def props(serverPaths: Seq[String]) = Props(new Initiator(serverPaths))
}
