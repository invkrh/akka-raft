package me.invkrh.raft.core

import scala.collection.mutable.ArrayBuffer

import akka.actor.ActorRef

import me.invkrh.raft.message.RaftMessage
import me.invkrh.raft.util.Logging

class MessageCache[T <: RaftMessage](id: Int) extends Logging {
  private val cache: ArrayBuffer[(ActorRef, T)] = new ArrayBuffer()
  def add(sender: ActorRef, msg: T): Unit = {
    cache.append((sender, msg))
    logDebug(s"[svr-$id] Message cached: $msg")
  }
  def flushTo(target: ActorRef): Unit = {
    val cnt = cache.size
    if (cache.nonEmpty) {
      cache foreach { case (src, msg) => target.tell(msg, src) }
      cache.clear()
    }
    logDebug(s"[svr-$id] Message flushed: $cnt")
  }
}
