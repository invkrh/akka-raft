package me.invkrh.raft.core

object Exception {
  
  def checkOrThrow(assert: Boolean, cause: Throwable): Unit = {
    if (!assert) throw cause
  }
  
  final case class EmptyInitMemberException()
      extends IllegalArgumentException("Members for bootstrap should not be empty")
  
  final case class HeartbeatIntervalException()
    extends IllegalArgumentException("Heartbeat interval should be smaller than the election time")
  
}
