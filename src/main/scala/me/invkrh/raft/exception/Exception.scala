package me.invkrh.raft.exception

import me.invkrh.raft.message.ClientMessage._
import me.invkrh.raft.message.RPCMessage._

final case class HeartbeatIntervalException()
  extends RuntimeException("Heartbeat interval should be smaller than the election time")

final case class MultiLeaderException(local: Int, received: Int, term: Int)
  extends RuntimeException(
    s"Two leader detected at term $term: local -> $local, received -> $received")

final case class CandidateHasLeaderException(leaderID: Int)
  extends RuntimeException(s"Leader should be empty, but $leaderID found")

final case class RaftConfigFileNotFoundException(path: String)
  extends RuntimeException(s"Can not find config file under $path")

final case class RaftConfigDirectoryNotFoundException()
  extends RuntimeException(s"Can not retrieve config directory location from System Env")

final case class UnexpectedSenderException(msg: String, senderAddr: String)
  extends RuntimeException(s"Receive message [$msg] from unexpected sender [$senderAddr]")

final case class MalformedAddressException(address: String, reason: String)
  extends RuntimeException(s"Address $address is not valid: $reason")

final case class UnreachableAddressException(address: String)
  extends RuntimeException(s"Address [$address] can not be reached")

final case class InvalidArgumentsException(argsStr: String)
  extends RuntimeException(s"Invalid arguments: $argsStr")

final case class EmptyMembershipException()
  extends RuntimeException(s"No members are given during initialization")

final case class InvalidResponseException(response: RPCResponse, curTerm: Int)
  extends RuntimeException(s"Response $response is not valid at term $curTerm")

final case class LogMatchingPropertyException(cmd1: Command, cmd2: Command)
  extends RuntimeException(s"Different commands [$cmd1, $cmd2] on the same index with same term")

final case class UnknownDataStoreTypeException(dst: String)
  extends RuntimeException(s"Data store type $dst does not exist")

final case class UnknownCommandException(cmd: Command)
  extends RuntimeException(s"Command $cmd is not valid")
