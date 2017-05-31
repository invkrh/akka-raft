package me.invkrh.raft.exception

import java.nio.file.Path

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

final case class UnknownInitializerException() extends RuntimeException("Unknown initializer")

final case class InvalidServerAddressException(address: String, details: String)
    extends RuntimeException(s"Server address is not valid: $address, see details: [$details]")
