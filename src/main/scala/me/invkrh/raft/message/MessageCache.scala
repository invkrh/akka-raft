package me.invkrh.raft.message

import scala.collection.mutable.ArrayBuffer

import akka.actor.ActorRef

import me.invkrh.raft.util.Logging

class MessageCache[T <: RaftMessage]() extends Logging {
  private val cache: ArrayBuffer[(ActorRef, T)] = new ArrayBuffer()
  def add(sender: ActorRef, msg: T): Unit = cache.append((sender, msg))
  def flushTo(target: ActorRef): Unit = {
    if (cache.nonEmpty) {
      cache foreach { case (src, msg) => target.tell(msg, src) }
      cache.clear()
    }
  }
}
