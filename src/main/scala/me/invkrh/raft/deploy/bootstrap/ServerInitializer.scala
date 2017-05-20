package me.invkrh.raft.deploy.bootstrap

import scala.collection.mutable.ArrayBuffer

import akka.actor.{Actor, ActorRef, Props}
import me.invkrh.raft.deploy.{AskServerID, Ready, ServerID}
import me.invkrh.raft.server.Message.Init

object ServerInitializer {
  def props(initialSize: Int) = Props(new ServerInitializer(initialSize))
}

class ServerInitializer(initialSize: Int) extends Actor {
  val members = new ArrayBuffer[ActorRef]()
  var remoteSysCnt = 0
  override def receive: Receive = {
    case AskServerID =>
      sender ! ServerID(remoteSysCnt)
      remoteSysCnt += 1
    case Ready(serverRef) =>
      members.append(serverRef)
      println(s"[${members.size}/$initialSize] Find server at: ${serverRef.path}")
      if (members.size == initialSize) {
        val membership = members.zipWithIndex.map(_.swap).toMap
        members foreach { _ ! Init(membership) }
        println("All servers are initialized")
        context.system.terminate()
      }
  }
}
