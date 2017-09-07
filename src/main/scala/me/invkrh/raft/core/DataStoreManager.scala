package me.invkrh.raft.core

import akka.actor.{Actor, Props}

import me.invkrh.raft.message.ClientMessage.LogEntry
import me.invkrh.raft.storage.DataStore
import me.invkrh.raft.util.Logging

case class ApplyLogsRequest(logs: List[LogEntry], commitIndex: Int)
case class CommandApplied(n: Int)

object DataStoreManager {
  def props(dataStore: DataStore): Props = {
    Props(new DataStoreManager(dataStore))
  }
}

class DataStoreManager(dataStore: DataStore) extends Actor with Logging {
  override def receive: Receive = {
    case ApplyLogsRequest(logs, commitIndex) =>
      logDebug("receive log: " + logs)
      logs foreach {
        case LogEntry(_, cmd, clientRef) =>
          clientRef ! dataStore.applyCommand(cmd)
      }
      sender ! CommandApplied(commitIndex)
  }
}
