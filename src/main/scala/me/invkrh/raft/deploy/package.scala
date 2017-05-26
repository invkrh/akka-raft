package me.invkrh.raft

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}

package object deploy {
  val daemonSystemName = "daemon-system"
  val coordinatorSystemName = "coordinator-system"

  val serverInitializerName = "initializer"
  val serverSpawnerName = "spawner"

  val raftServerName = "raft-server"
  
  def initializerAddress(configFile: File): String = {
    val config = ConfigFactory.parseFile(configFile)
    initializerAddress(config)
  }
  
  def initializerAddress(config: Config): String = {
    val hostname = config.getString("coordinator.hostname")
    val port = config.getInt("coordinator.port")
    s"akka://$coordinatorSystemName@$hostname:$port/user/$serverInitializerName"
  }
  
  def serverAddress(config: Config): String = {
    val hostname = config.getString("coordinator.hostname")
    val port = config.getInt("coordinator.port")
    s"akka://$coordinatorSystemName@$hostname:$port/user/$raftServerName-0"
  }
}
