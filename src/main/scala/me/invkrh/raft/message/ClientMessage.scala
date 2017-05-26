package me.invkrh.raft.message

sealed trait ClientMessage extends RaftMessage
case class Command(key: String, value: Int) extends ClientMessage {
  override def toString: String = s"$key = $value"
}
case class CommandResponse(success: Boolean, info: String = "")
case class LogEntry(term: Int, command: Command)

