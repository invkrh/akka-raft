package me.invkrh.raft.core

object State extends Enumeration {
  val Init, Leader, Follower, Candidate = Value
}
