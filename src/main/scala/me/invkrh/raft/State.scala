package me.invkrh.raft

object State extends Enumeration {
  val Leader, Follower, Candidate = Value
}
