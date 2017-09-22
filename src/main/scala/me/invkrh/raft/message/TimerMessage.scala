package me.invkrh.raft.message

object TimerMessage {
  case object Tick extends RaftMessage
  case object StartElection extends RaftMessage
}
