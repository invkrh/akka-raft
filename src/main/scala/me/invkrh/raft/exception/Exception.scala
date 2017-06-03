package me.invkrh.raft.exception

import java.nio.file.Path

import akka.actor.ActorRef

import me.invkrh.raft.message.RaftMessage

final case class HeartbeatIntervalException()
    extends IllegalArgumentException("Heartbeat interval should be smaller than the election time")

final case class InvalidLeaderException(local: Int, received: Int, term: Int)
    extends RuntimeException(
      s"Two leader detected at term $term: local -> $local, received -> $received"
    )

final case class CandidateHasLeaderException(leaderID: Int)
    extends RuntimeException(s"Leader should be empty, but $leaderID found")

final case class RaftConfigurationFileNotFoundException(configFilePath: Path)
    extends RuntimeException(s"Raft conf file does not exist: $configFilePath")

final case class UnexpectedSenderException(msg: RaftMessage, sender: ActorRef)
    extends RuntimeException(
      s"Receive message [$msg] from unknown sender [${sender.path.address}]"
    )

final case class MalformedAddressException(address: String)
    extends RuntimeException(s"Server address is not valid: $address")

final case class UnreachableAddressException(address: String)
    extends RuntimeException(s"Address [$address] can not be reached")

final case class InvalidArgumentException(argsStr: String)
    extends RuntimeException(s"Invalid arguments: $argsStr")
