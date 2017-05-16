package me.invkrh.raft

import java.io.File

package object deploy {
  val raftSystemName = "raft-system"
  val raftServerName = "raft-server"
  case class AdminConfig(configFile: File = new File("."), action: String = "")
}
