package me.invkrh.raft.util

import org.slf4j.LoggerFactory

trait Logging {
  private val logger = LoggerFactory.getLogger(this.getClass)
  def info(msg: String): Unit = logger.info(msg)
  def warn(msg: String): Unit = logger.debug(msg)
  def error(msg: String): Unit = logger.error(msg)
}
