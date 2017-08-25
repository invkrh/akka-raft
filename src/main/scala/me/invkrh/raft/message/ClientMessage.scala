package me.invkrh.raft.message

import akka.actor.ActorRef

sealed trait ClientMessage

case class ClientRequest(clientId: Int, sequenceNum: Int, command: Command) extends ClientMessage
case class ClientQuery(query: String) extends ClientMessage
case class ClientRPCResult(response: CommandResult, leaderHint: Option[ActorRef])

case object RegisterClient extends ClientMessage
case class RegisterClientResult(status: Boolean, clientId: Int, leaderHint: Option[ActorRef])
    extends ClientMessage

sealed trait Command extends RaftMessage
case class SET(key: String, value: Int) extends Command {
  override def toString: String = s"Set $key = $value"
}
case class DEL(key: String) extends Command {
  override def toString: String = s"Remove $key"
}
case class GET(key: String) extends Command {
  override def toString: String = s"Get $key"
}
case object Init extends Command {
  override def toString: String = s"Init"
}

sealed trait CommandResult extends RaftMessage
case class CommandSuccess(payload: Option[Any]) extends CommandResult
case class CommandFailure(info: String) extends CommandResult

case class LogEntry(term: Int, command: Command, clientRef: ActorRef = null)
