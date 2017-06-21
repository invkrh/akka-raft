package me.invkrh.raft.deploy.actor

import akka.actor.{Actor, ActorRef, Props}

import me.invkrh.raft.exception.UnexpectedSenderException
import me.invkrh.raft.message.{Register, ServerId, ServerIdRequest}
import me.invkrh.raft.server.{Server, ServerConf}
import me.invkrh.raft.util.Logging

object ServerLauncher {
  def props(initializerRef: ActorRef, serverConf: ServerConf): Props =
    Props(new ServerLauncher(initializerRef, serverConf))
}

class ServerLauncher(initializerRef: ActorRef, serverConf: ServerConf) extends Actor with Logging {

  initializerRef ! ServerIdRequest

  override def receive: Receive = {
    case msg @ ServerId(id) =>
      if (sender != initializerRef) {
        throw UnexpectedSenderException(msg.toString, sender.path.address.toString)
      } else if (id < 0) {
        logInfo(s"Initial size reached, no server will be created")
        logInfo("Shutting down server launcher")
        context.stop(self)
      } else {
        val server = Server.run(id, serverConf)(context.system)
        logInfo(s"Server $id has been created at ${server.path}, wait for initialization")
        initializerRef.tell(Register(id), server)
        logInfo(s"Shutting down server launcher")
        context.stop(self)
      }
  }
}
