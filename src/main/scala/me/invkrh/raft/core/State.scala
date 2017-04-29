package me.invkrh.raft.core

object State extends Enumeration {
  val Unknown, Leader, Follower, Candidate = Value
}
