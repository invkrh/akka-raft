package me.invkrh.raft.util

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

import akka.actor.{ActorRef, Cancellable, Scheduler}
import me.invkrh.raft.core.Message._

trait Timer {
  var cancellable: Cancellable = _
  def start(): Unit
  def restart(): Unit = {
    stop()
    start()
  }
  def stop(): Unit = {
    if (cancellable != null && !cancellable.isCancelled) {
      cancellable.cancel()
    }
  }
}

class FixedTimer(duration: FiniteDuration, event: EventMessage)(implicit scheduler: Scheduler,
                                                                target: ActorRef)
    extends Timer {
  def start(): Unit = {
    cancellable = scheduler.scheduleOnce(duration, target, event)
  }
}

class RandomizedTimer(min: FiniteDuration, max: FiniteDuration, event: EventMessage)(
  implicit scheduler: Scheduler,
  target: ActorRef)
    extends Timer {
  def start(): Unit = {
    val rd = min.toMillis + Random.nextInt((max.toMillis - min.toMillis + 1).toInt)
    cancellable = scheduler.scheduleOnce(rd milliseconds, target, event)
  }
}

class PeriodicTimer(duration: FiniteDuration, event: EventMessage)(implicit scheduler: Scheduler,
                                                                   target: ActorRef)
    extends Timer {
  def start(): Unit = {
    cancellable = scheduler.schedule(Duration.Zero, duration, target, event)
  }
}
