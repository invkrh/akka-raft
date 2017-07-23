package me.invkrh.raft.util

import org.slf4j.{Logger, LoggerFactory}

trait Logging {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private def render(msg: String) = s"$loggingPrefix $msg".trim
  def loggingPrefix: String = ""
  def logDebug(msg: String): Unit = logger.debug(render(msg))
  def logInfo(msg: String): Unit = logger.info(render(msg))
  def logWarn(msg: String): Unit = logger.warn(render(msg))
  def logError(msg: String): Unit = logger.error(render(msg))
}
