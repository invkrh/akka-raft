package me.invkrh.raft.deploy.bootstrap

import akka.actor.{Actor, ActorRef, Props}
import me.invkrh.raft.deploy.{AskServerID, Ready, ServerID}
import me.invkrh.raft.server.Message.Init

object ServerInitializer {
  def props(initialSize: Int): Props = Props(new ServerInitializer(initialSize))
}

class ServerInitializer(initialSize: Int) extends Actor {
  var membership: Map[Int, ActorRef] = Map()
  var remoteSysCnt = 0
  // TODO: need to deploy remotely ? as an over watcher
  override def receive: Receive = {
    case AskServerID =>
      sender ! ServerID(remoteSysCnt)
      remoteSysCnt += 1
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
        context.system.terminate()
      }
  }
}
