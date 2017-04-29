package me.invkrh.raft.core

import scala.collection.mutable.ArrayBuffer

import akka.actor.ActorRef
import me.invkrh.raft.core.Message.RaftMessage
import me.invkrh.raft.util.Logging

class MessageCache[T <: RaftMessage](serverId: Int, name: String = "") extends Logging {
  override val logPrefix: String = s"[$serverId] [$name]"
  private val cache: ArrayBuffer[(ActorRef, T)] = new ArrayBuffer()

  def add(sender: ActorRef, msg: T): Unit = cache.append((sender, msg))
  def flushTo(target: ActorRef): Unit = {
    if (cache.isEmpty) {
      info("Cache is empty, no resending")
    } else {
      info(s"${cache.size} cached messages found, resending")
      cache foreach { case (src, msg) => target.tell(msg, src) }
      cache.clear()
    }
  }
}
