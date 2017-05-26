package me.invkrh.raft.deploy.coordinator

import akka.actor.{Actor, ActorRef, Props}

import me.invkrh.raft.message.{Init, Register, ServerId, ServerIdRequest}
import me.invkrh.raft.server.ServerConf

object ServerInitializer {
  def props(initialSize: Int): Props =
    Props(new ServerInitializer(initialSize))
}

class ServerInitializer(initialSize: Int) extends Actor {

  private var membership: Map[Int, ActorRef] = Map()
  private var memberCount = 0

  // TODO: need to deploy remotely ? as an over watcher
  override def receive: Receive = {
    case ServerIdRequest =>
      if (memberCount < initialSize) {
        sender ! ServerId(memberCount)
        memberCount += 1
      } else {
        sender ! ServerId(-1)
      }
    case Register(id) =>
      membership = membership.updated(id, sender)
      // scalastyle:off println
      println(s"[${membership.size}/$initialSize] Find server at: ${sender().path}")
      // scalastyle:on println
      if (membership.size == initialSize) {
        membership foreach { case (_, server) => server ! Init(membership) }
        // scalastyle:off println
        println("All servers are initialized")
        // scalastyle:on println
        context.stop(self)
      }
  }
}
