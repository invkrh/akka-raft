package me.invkrh.raft.message

sealed trait TimerMessage
case object Tick extends TimerMessage
case object StartElection extends TimerMessage
