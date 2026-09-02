package sagablind

import org.slf4j.LoggerFactory

// ── Slf4jLogger ───────────────────────────────────────────────────────────────
// SagaLogger implementation backed by SLF4J/logback.
// Used in goblin-world to route engine logs through logback.xml.

class Slf4jLogger(name: String) extends SagaLogger:
  private val logger = LoggerFactory.getLogger(name)

  def debug(component: String, msg: => String): Unit =
    if logger.isDebugEnabled then logger.debug(s"[$component] $msg")

  def info(component: String, msg: => String): Unit =
    if logger.isInfoEnabled then logger.info(s"[$component] $msg")

  def warn(component: String, msg: => String): Unit =
    if logger.isWarnEnabled then logger.warn(s"[$component] $msg")

  def error(component: String, msg: => String): Unit =
    logger.error(s"[$component] $msg")
