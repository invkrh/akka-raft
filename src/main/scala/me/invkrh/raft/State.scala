package me.invkrh.raft

object State extends Enumeration {
  val Unknown, Leader, Follower, Candidate = Value
}
