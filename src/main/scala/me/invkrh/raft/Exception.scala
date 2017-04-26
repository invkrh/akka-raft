package me.invkrh.raft

object Exception {
  class RaftException(errorMsg: String) extends Exception(errorMsg)
}
