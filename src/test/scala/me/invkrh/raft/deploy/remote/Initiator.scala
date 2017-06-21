package me.invkrh.raft.deploy.remote

import com.typesafe.config.Config

import me.invkrh.raft.util.ConfigHolder

class Initiator(implicit val config: Config) extends ClusterInitiatorRemote with ConfigHolder
