package sagablind

// ── SagaLogger ───────────────────────────────────────────────────────────────
// Pluggable logging abstraction for saga-blind.
// Same spirit as saga-graph's SagaLogger — injected, not hardcoded.
//
// Implementations:
//   SagaLogger.noOp    — silent, for tests (default)
//   SagaLogger.stdout  — println to stdout, for development
//
// Log format: [LEVEL] [component] [sagaId?] message
//   component: engine | zh | fw (FileWatcher) | runtime
//   sagaId:    first 8 chars of the saga instance id, or - if not in saga context
//
// The component is passed explicitly — saga-blind has multiple concurrent
// components (engine + ZombieHunter) that share the same WAL and must be
// distinguishable in logs.

trait SagaLogger:
  def debug(component: String, msg: => String): Unit
  def info(component: String, msg: => String):  Unit
  def warn(component: String, msg: => String):  Unit
  def error(component: String, msg: => String): Unit

  // Convenience: bind component once, use like a regular logger
  def forComponent(component: String): BoundLogger = BoundLogger(this, component)

class BoundLogger(logger: SagaLogger, component: String):
  def debug(msg: => String): Unit = logger.debug(component, msg)
  def info(msg: => String):  Unit = logger.info(component, msg)
  def warn(msg: => String):  Unit = logger.warn(component, msg)
  def error(msg: => String): Unit = logger.error(component, msg)

object SagaLogger:

  val noOp: SagaLogger = new SagaLogger:
    def debug(component: String, msg: => String): Unit = ()
    def info(component: String, msg: => String):  Unit = ()
    def warn(component: String, msg: => String):  Unit = ()
    def error(component: String, msg: => String): Unit = ()

  val stdout: SagaLogger = new SagaLogger:
    private def sagaCtx: String =
      SagaContext.current
        .map(id => s"[${id.value.take(8)}]")
        .getOrElse("[-]")

    def debug(component: String, msg: => String): Unit =
      Predef.println(s"[DEBUG] [$component] $sagaCtx $msg")
    def info(component: String, msg: => String):  Unit =
      Predef.println(s"[INFO]  [$component] $sagaCtx $msg")
    def warn(component: String, msg: => String):  Unit =
      Predef.println(s"[WARN]  [$component] $sagaCtx $msg")
    def error(component: String, msg: => String): Unit =
      Predef.println(s"[ERROR] [$component] $sagaCtx $msg")
