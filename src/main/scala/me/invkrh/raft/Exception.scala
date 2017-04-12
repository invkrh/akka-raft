package me.invkrh.raft

import akka.actor.ActorRef
import me.invkrh.raft.Message.RPCMessage

object Exception {

  class RaftException(errorMsg: String) extends Exception(errorMsg)

  class IrrelevantMessageException private (errorMsg: String)
      extends RaftException(errorMsg: String) {
    def this(rpcMessage: RPCMessage, sender: ActorRef) =
      this(s"Irrelevant message [ $rpcMessage ] received from ${sender.path}")
  }

}
