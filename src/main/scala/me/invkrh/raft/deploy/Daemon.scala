package me.invkrh.raft.deploy

import java.nio.file.{Files, Paths}

import com.typesafe.config.{Config, ConfigFactory}

import me.invkrh.raft.deploy.remote.DaemonSystem
import me.invkrh.raft.exception.{InvalidArgumentException, RaftConfigurationFileNotFoundException}

object Daemon {

  def main(args: Array[String]): Unit = {
    require(args.length == 2)
    val configFilePath = Paths.get(args.head)
    if (!Files.exists(configFilePath)) {
      throw RaftConfigurationFileNotFoundException(configFilePath)
    }
    val config = ConfigFactory.parseFile(configFilePath.toFile)
    apply(config, args(1))
  }

  def apply(argsStr: String*): Unit = main(argsStr.toArray)

  def apply(config: Config, action: String): Unit = {
    action match {
      case "init" => new DaemonSystem(config, isPrecursorSystem = true).init()
      case "join" => new DaemonSystem(config).join()
      case "stop" => new DaemonSystem(config).stop()
      case "stop-all" => new DaemonSystem(config).stopAll()
      case _ => throw InvalidArgumentException(action)
    }
  }
}
