package me.invkrh.raft.deploy.daemon

import akka.actor.{Actor, ActorRef, Props}

import me.invkrh.raft.deploy._
import me.invkrh.raft.exception.UnknownInitializerException
import me.invkrh.raft.message.{Register, ServerId, ServerIdRequest}
import me.invkrh.raft.server.{Server, ServerConf}
import me.invkrh.raft.util.Logging

object ServerSpawner {
  def props(initializerRef: ActorRef, serverConf: ServerConf): Props =
    Props(new ServerSpawner(initializerRef, serverConf))
}

class ServerSpawner(initializerRef: ActorRef, serverConf: ServerConf) extends Actor with Logging {
  initializerRef ! ServerIdRequest
  override def receive: Receive = {
    case ServerId(id) =>
      if (sender != initializerRef) {
        throw UnknownInitializerException()
      } else if (id < 0) {
        logInfo(s"Initial size reached, no server will be created")
        context.system.terminate()
      } else {
        val server = Server.run(id, serverConf)(context.system)
        logInfo(s"Server $id has been created at ${server.path}, wait for initialization")
        initializerRef.tell(Register(id), server)
        context.stop(self)
      }
  }
}
