package sagablind

import org.slf4j.LoggerFactory

// ── Slf4jLogger ───────────────────────────────────────────────────────────────
// SagaLogger implementation backed by SLF4J/logback.
// Includes sagaId from SagaContext when available.

class Slf4jLogger(name: String) extends SagaLogger:
  private val logger = LoggerFactory.getLogger(name)

  private def sagaCtx: String =
    SagaContext.current
      .map(id => s"[${id.value.take(8)}] ")
      .getOrElse("")

  def debug(component: String, msg: => String): Unit =
    if logger.isDebugEnabled then logger.debug(s"[$component] $sagaCtx$msg")

  def info(component: String, msg: => String): Unit =
    if logger.isInfoEnabled then logger.info(s"[$component] $sagaCtx$msg")

  def warn(component: String, msg: => String): Unit =
    if logger.isWarnEnabled then logger.warn(s"[$component] $sagaCtx$msg")

  def error(component: String, msg: => String): Unit =
    logger.error(s"[$component] $sagaCtx$msg")
