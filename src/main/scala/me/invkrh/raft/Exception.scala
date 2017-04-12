package me.invkrh.raft.util

import akka.actor.ActorRef
import me.invkrh.raft.RPCMessage.Message

object Exception {

  class RaftException(errorMsg: String) extends Exception(errorMsg)

  class IrrelevantMessageException private (errorMsg: String)
      extends RaftException(errorMsg: String) {
    def this(rpcMessage: Message, sender: ActorRef) =
      this(s"Irrelevant message [ $rpcMessage ] received from ${sender.path}")
  }

}
