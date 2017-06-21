package me.invkrh.raft.exception

final case class HeartbeatIntervalException()
    extends RuntimeException("Heartbeat interval should be smaller than the election time")

final case class InvalidLeaderException(local: Int, received: Int, term: Int)
    extends RuntimeException(
      s"Two leader detected at term $term: local -> $local, received -> $received"
    )

final case class CandidateHasLeaderException(leaderID: Int)
    extends RuntimeException(s"Leader should be empty, but $leaderID found")

final case class RaftConfigFileNotFoundException(path: String)
    extends RuntimeException(s"Can not find config file under $path")

final case class RaftConfigDirectoryNotFoundException()
    extends RuntimeException(s"Can not retrieve config directory from System Env")

final case class UnexpectedSenderException(msg: String, senderAddr: String)
    extends RuntimeException(s"Receive message [$msg] from unexpected sender [$senderAddr]")

final case class MalformedAddressException(address: String)
    extends RuntimeException(s"Server address is not valid: $address")

final case class UnreachableAddressException(address: String)
    extends RuntimeException(s"Address [$address] can not be reached")

final case class InvalidArgumentsException(argsStr: String)
    extends RuntimeException(s"Invalid arguments: $argsStr")
