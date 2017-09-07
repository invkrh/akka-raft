package me.invkrh.raft.message

object DeployMessage {
  case object ServerIdRequest extends RaftMessage
  case class ServerId(id: Int) extends RaftMessage
  case class Register(id: Int) extends RaftMessage
}
