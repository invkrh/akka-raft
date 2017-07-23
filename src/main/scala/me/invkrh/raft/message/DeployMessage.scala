package me.invkrh.raft.message

sealed trait DeployMessage extends RaftMessage
case object ServerIdRequest extends DeployMessage
case class ServerId(id: Int) extends DeployMessage
case class Register(id: Int) extends DeployMessage
