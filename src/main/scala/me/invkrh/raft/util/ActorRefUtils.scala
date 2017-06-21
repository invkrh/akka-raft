package me.invkrh.raft.util

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import akka.actor.{ActorNotFound, ActorRef, ActorSystem}

import me.invkrh.raft.deploy.raftSystemName
import me.invkrh.raft.exception.UnreachableAddressException

object ActorRefUtils {

  def resolveRefByName(systemName: String, hostName: String, port: Int, actorName: String)(
    implicit system: ActorSystem,
    timeout: FiniteDuration
  ): ActorRef = {
    val target = s"akka://$raftSystemName@$hostName:$port/user/$actorName"
    try {
      Await.result(system.actorSelection(target).resolveOne(timeout), timeout)
    } catch {
      case _: ActorNotFound => throw UnreachableAddressException(target)
    }
  }

  def resolveRefByName(systemName: String, address: CanonicalAddress, actorName: String)(
    implicit system: ActorSystem,
    timeout: FiniteDuration
  ): ActorRef = {
    resolveRefByName(systemName, address.hostName, address.port, actorName)
  }

  def resolveRefByName(systemName: String, address: String, actorName: String)(
    implicit system: ActorSystem,
    timeout: FiniteDuration
  ): ActorRef = {
    resolveRefByName(systemName, CanonicalAddress(address), actorName)
  }
}
