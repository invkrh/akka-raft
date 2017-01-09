package me.invkrh.raft.util

trait Metric extends Logging {
  def timer[T](name: String)(func: => T): T = {
    val start = System.currentTimeMillis()
    val res = func
    val time = System.currentTimeMillis() - start
    info(s"$name takes $time ms")
    res
  }
}
