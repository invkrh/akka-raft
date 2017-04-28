package me.invkrh.raft.util

import scala.util.Random

object UID {
  def apply(digit: Int = 5): String = Random.alphanumeric.take(digit).mkString.toUpperCase
}
