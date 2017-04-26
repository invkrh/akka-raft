package me.invkrh.raft.util

import org.slf4j.{Logger, LoggerFactory}

trait Logging {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private def render(msg: String) = s"$logPrefix $msg".trim
  def logPrefix: String = ""
  def debug(msg: String): Unit = logger.debug(render(msg))
  def info(msg: String): Unit = logger.info(render(msg))
  def warn(msg: String): Unit = logger.warn(render(msg))
  def error(msg: String): Unit = logger.error(render(msg))
}
