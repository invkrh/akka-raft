package me.invkrh.raft

import java.io.File

import akka.actor.ActorRef

package object deploy {
  val daemonSystemName = "daemon-system"
  val bootstrapSystemName = "bootstrap-system"

  val serverInitializerName = "initializer"
  val serverSpawnerName = "spawner"

  val raftServerName = "raft-server"

  case object AskServerID
  case class ServerID(id: Int)
  case class Ready(serverRef: ActorRef)

  case class AdminConfig(configFile: File = new File("."), action: String = "")
  case class BootstrapConfig(configFile: File = new File("."), action: String = "")
}
