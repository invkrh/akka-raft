package me.invkrh.raft.core

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

import akka.actor.{ActorRef, Cancellable, Scheduler}

import me.invkrh.raft.message.RaftMessage

trait Timer {
  protected var cancellable: Cancellable = _

  def start(): Unit
  def stop(): Unit = {
    if (cancellable != null && !cancellable.isCancelled) {
      cancellable.cancel()
    }
  }
  def restart(): Unit = {
    stop()
    start()
  }
}

class RandomizedTimer(min: FiniteDuration, max: FiniteDuration, event: RaftMessage)(
    implicit scheduler: Scheduler,
    executor: ExecutionContext,
    target: ActorRef)
  extends Timer {
  def start(): Unit = {
    require(target != null, "Timer target can not be null")
    val rd = min.toMillis + Random.nextInt((max.toMillis - min.toMillis + 1).toInt)
    cancellable = scheduler.scheduleOnce(rd milliseconds, target, event)
  }
}

class PeriodicTimer(duration: FiniteDuration, event: RaftMessage)(
    implicit scheduler: Scheduler,
    executor: ExecutionContext,
    target: ActorRef)
  extends Timer {
  def start(): Unit = {
    require(target != null, "Timer target can not be null")
    cancellable = scheduler.schedule(Duration.Zero, duration, target, event)
  }
}
