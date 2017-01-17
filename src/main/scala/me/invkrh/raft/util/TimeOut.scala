package me.invkrh.raft.util

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration

import akka.actor.{Cancellable, Scheduler}

class TimeOut(duration: FiniteDuration, action: => Unit)(implicit scheduler: Scheduler) {
  private var cancellable: Cancellable = _
  def reset(): Unit = {
    stop()
    cancellable = scheduler.scheduleOnce(duration)(action)
  }
  def stop(): Unit = {
    if (cancellable != null) {
      cancellable.cancel()
    }
  }
}
