package me.invkrh.raft.util

import java.nio.file.{Files, Paths}

import com.typesafe.config.{Config, ConfigFactory}

import me.invkrh.raft.exception.{
  RaftConfigDirectoryNotFoundException,
  RaftConfigFileNotFoundException
}

trait ConfigHolder {
  val config: Config
}

trait RaftConfig extends ConfigHolder {
  override val config: Config = {
    val confDir = System.getenv("RAFT_CONF_DIR")
    if (confDir == null) {
      throw RaftConfigDirectoryNotFoundException()
    } else {
      val configPath = Paths.get(confDir + "/raft.conf")
      if (Files.exists(configPath)) {
        ConfigFactory.parseFile(configPath.toFile)
      } else {
        throw RaftConfigFileNotFoundException(configPath.toString)
      }
    }
  }
}
