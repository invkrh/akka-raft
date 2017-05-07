package me.invkrh.raft.core

object State extends Enumeration {
  val Bootstrap = Value("Boot")
  val Follower = Value("Foll")
  val Candidate = Value("Cand")
  val Leader = Value("Lead")
}
