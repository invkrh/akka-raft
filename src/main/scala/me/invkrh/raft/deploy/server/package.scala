package me.invkrh.raft.deploy

import java.io.File

package object server {
  val raftSystemName = "raft-system"
  val raftServerName = "raft-server"
  case object ResolutionTimeout
  case class InitConfig(memberFile: File = new File("."))
  case class StartConfig(configFile: File = new File("."))
}
