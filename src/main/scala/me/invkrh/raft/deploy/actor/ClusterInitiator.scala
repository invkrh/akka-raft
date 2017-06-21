package me.invkrh.raft.deploy.actor

import akka.actor.{Actor, ActorRef, Props}

import me.invkrh.raft.message.{Membership, Register, ServerId, ServerIdRequest}
import me.invkrh.raft.util.Logging

object ClusterInitiator {
  def props(initialSize: Int): Props =
    Props(new ClusterInitiator(initialSize))
}

class ClusterInitiator(initialSize: Int) extends Actor with Logging {

  private var membership: Map[Int, ActorRef] = Map()
  private var memberCount = 0

  override def receive: Receive = {
    case ServerIdRequest =>
      if (memberCount < initialSize) {
        memberCount += 1
        sender ! ServerId(memberCount)
      } else {
        sender ! ServerId(-1)
      }
    case Register(id) =>
      membership = membership.updated(id, sender)
      logInfo(s"[${membership.size}/$initialSize] Find server at: ${sender().path}")
      if (membership.size == initialSize) {
        membership foreach { case (_, server) => server ! Membership(membership) }
        logInfo("All servers will be initialized")
        logInfo("Shutting down Initiator")
        context.stop(self)
        context.system.terminate()
      }
  }
}
