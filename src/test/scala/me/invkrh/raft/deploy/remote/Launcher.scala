package me.invkrh.raft.deploy.remote

import com.typesafe.config.Config

import me.invkrh.raft.util.ConfigHolder

class Launcher(implicit val config: Config) extends ServerLauncherRemote with ConfigHolder {}
