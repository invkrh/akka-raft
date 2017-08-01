package me.invkrh.raft.message

sealed trait Command extends RaftMessage
case class Set(key: String, value: Int) extends Command {
  override def toString: String = s"set $key = $value"
}
case class Remove(key: String) extends Command {
  override def toString: String = s"remove $key"
}
case class Get(key: String) extends Command {
  override def toString: String = s"get $key"
}
case object Init extends Command {
  override def toString: String = s"Void"
}
case class CommandResponse(success: Boolean, info: String = "")
case class LogEntry(term: Int, command: Command)
