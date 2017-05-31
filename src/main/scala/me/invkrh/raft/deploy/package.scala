package me.invkrh.raft

import com.typesafe.config.Config

import me.invkrh.raft.server.Server.raftServerName

package object deploy {
  val daemonSystemName = "daemon-system"
  val coordinatorSystemName = "coordinator-system"

  val serverInitializerName = "initializer"
  val serverSpawnerName = "spawner"

  def initializerAddress(config: Config): String = {
    val hostname = config.getString("coordinator.hostname")
    val port = config.getInt("coordinator.port")
    s"akka://$coordinatorSystemName@$hostname:$port/user/$serverInitializerName"
  }

  def serverAddress(config: Config): String = {
    val hostName = config.getString("coordinator.hostname")
    val port = config.getInt("coordinator.port")
    s"akka://$coordinatorSystemName@$hostName:$port/user/$raftServerName-*"
  }

  def serverAddress(hostName: String, port: Int): String = {
    s"akka://$coordinatorSystemName@$hostName:$port/user/$raftServerName-*"
  }
}
