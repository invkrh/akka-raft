package me.invkrh.raft.deploy.remote

import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, _}

import akka.actor.{ActorNotFound, ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout

import me.invkrh.raft.deploy.{raftServerName, raftSystemName, serverInitializerName}
import me.invkrh.raft.exception.UnreachableAddressException
import me.invkrh.raft.message.{GetStatus, Status}
import me.invkrh.raft.util.CanonicalAddress

class PrecursorSystemResolver(address: String,
                              timeout: FiniteDuration = 5.seconds)(implicit system: ActorSystem) {

  // cache resolved ActorRef
  var initializerRef: ActorRef = _
  var serverRef: ActorRef = _

  def resolveRefByName(name: String): ActorRef = {
    val ca = CanonicalAddress(address)
    val target = s"akka://$raftSystemName@${ca.hostName}:${ca.port}/user/$name"
    try {
      Await.result(system.actorSelection(target).resolveOne(timeout), timeout)
    } catch {
      case _: ActorNotFound => throw UnreachableAddressException(target)
    }
  }

  def initializer: ActorRef = {
    if (initializerRef == null) {
      initializerRef = resolveRefByName(serverInitializerName)
    }
    initializerRef
  }

  def server: ActorRef = {
    if (serverRef == null) {
      serverRef = resolveRefByName(raftServerName + "*")
    }
    serverRef
  }

  def leaderID: Option[Int] = {
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(5.seconds)
    val leadFuture =
      for {
        Status(_, _, _, lead) <- server ? GetStatus
      } yield {
        lead
      }
    Await.result(leadFuture, 5.seconds)
  }
}
