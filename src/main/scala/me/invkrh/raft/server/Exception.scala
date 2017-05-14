package me.invkrh.raft.server

import akka.actor.ActorRef
import me.invkrh.raft.server.Message.RaftMessage

object Exception {

  def checkOrThrow(assert: Boolean, cause: Throwable): Unit = {
    if (!assert) throw cause
  }

//  final case class EmptyInitMemberException()
//      extends IllegalArgumentException("Members for bootstrap should not be empty")

  final case class HeartbeatIntervalException()
      extends IllegalArgumentException(
        "Heartbeat interval should be smaller than the election time"
      )

  final case class LeaderNotUniqueException(local: Int, received: Int)
      extends RuntimeException(s"Two leader detected: local -> $local, received -> $received")

  final case class CandidateHasLeaderException(leaderID: Int)
      extends RuntimeException(s"Leader should be empty, but $leaderID found")

//  final case class IrrelevantMessageException(msg: RaftMessage, sender: ActorRef)
//      extends RuntimeException(s"Irrelevant messages found: $msg, from ${sender.path}")
}
