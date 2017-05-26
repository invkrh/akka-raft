package me.invkrh.raft.deploy.coordinator

import akka.actor.{Actor, ActorRef, Props}

import me.invkrh.raft.message.{AskServerID, Init, Ready, ServerID}
import me.invkrh.raft.server.ServerConf

object ServerInitializer {
  def props(initialSize: Int, serverConf: ServerConf): Props =
    Props(new ServerInitializer(initialSize, serverConf))
}

class ServerInitializer(initialSize: Int, serverConf: ServerConf) extends Actor {
  
  private var membership: Map[Int, ActorRef] = Map()
  private var remoteSysCnt = membership.size

  // TODO: need to deploy remotely ? as an over watcher
  override def receive: Receive = {
    case AskServerID =>
      if (remoteSysCnt < initialSize) {
        sender ! ServerID(remoteSysCnt)
        remoteSysCnt += 1
      } else {
        sender ! ServerID(-1)
      }
    case Ready(id, serverRef) =>
      membership = membership.updated(id, serverRef)
      // scalastyle:off println
      println(s"[${membership.size}/$initialSize] Find server at: ${serverRef.path}")
      // scalastyle:on println
      if (membership.size == initialSize) {
        membership foreach { case (_, server) => server ! Init(membership) }
        // scalastyle:off println
        println("All servers are initialized")
        // scalastyle:on println
      }
  }
}
