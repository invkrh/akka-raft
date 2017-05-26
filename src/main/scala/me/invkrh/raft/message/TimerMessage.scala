package me.invkrh.raft.message

sealed trait TimerMessage extends RaftMessage
case object Tick extends TimerMessage
case object StartElection extends TimerMessage
