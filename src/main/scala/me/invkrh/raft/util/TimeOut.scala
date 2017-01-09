package me.invkrh.raft.util

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration

import akka.actor.{Cancellable, Scheduler}

class TimeOut(duration: FiniteDuration, action: => Unit)(implicit scheduler: Scheduler) {
  private var cancellable: Cancellable = scheduler.scheduleOnce(duration)(action)
  def reset(): Unit = {
    cancellable.cancel()
    cancellable = scheduler.scheduleOnce(duration)(action)
  }
  def stop(): Unit = { cancellable.cancel() }
}
