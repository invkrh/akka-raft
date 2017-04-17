package me.invkrh.raft.util

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

import akka.actor.{Cancellable, Scheduler}

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

class FixedTimer(durationInMills: Int, handler: => Unit)(implicit scheduler: Scheduler)
    extends Timer {
  private val duration = durationInMills milliseconds
  def start(): Unit = {
    cancellable = scheduler.scheduleOnce(duration)(handler)
  }
}

class RandomizedTimer(minMills: Int, maxMills: Int, handler: => Unit)(
  implicit scheduler: Scheduler)
    extends Timer {
  def start(): Unit = {
    val rd = minMills + Random.nextInt(maxMills - minMills)
    cancellable = scheduler.scheduleOnce(rd milliseconds)(handler)
  }
}

class PeriodicTimer(durationInMills: Int, handler: => Unit)(
  implicit scheduler: Scheduler)
    extends Timer {
  private val duration = durationInMills milliseconds
  def start(): Unit = {
    cancellable = scheduler.schedule(Duration.Zero, duration)(handler)
  }
}
