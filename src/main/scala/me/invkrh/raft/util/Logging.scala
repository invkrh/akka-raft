package me.invkrh.raft.util

import org.slf4j.{Logger, LoggerFactory}

trait Logging {
  protected val logger: Logger = LoggerFactory.getLogger(this.getClass)
  def debug(msg: String): Unit = logger.debug(msg)
  def info(msg: String): Unit = logger.info(msg)
  def warn(msg: String): Unit = logger.warn(msg)
  def error(msg: String): Unit = logger.error(msg)
}

trait IdentifyLogging extends Logging{
  def id: Int
  override def debug(msg: String): Unit = logger.debug(s"[$id] $msg")
  override def info(msg: String): Unit = logger.info(s"[$id] $msg")
  override def warn(msg: String): Unit = logger.warn(s"[$id] $msg")
  override def error(msg: String): Unit = logger.error(s"[$id] $msg")
}
