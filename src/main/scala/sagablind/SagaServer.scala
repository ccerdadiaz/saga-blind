package sagablind

import ujson.*

// ── SagaServer ───────────────────────────────────────────────────────────────
// HTTP entry point — extends cask.Main directly.
// Cask.Main blocks the main thread and handles the server lifecycle.
// Configuration via environment variables (see below).
//
//   SAGA_BLIND_DB       path to SQLite DB       (default: saga-blind.db)
//   SAGA_BLIND_WATCH    path to watched dir     (default: ./definitions)
//   SAGA_BLIND_HOST     HTTP bind host          (default: 0.0.0.0)
//   SAGA_BLIND_PORT     HTTP port               (default: 7777)

object SagaServer extends cask.Main:

  val dbPath   = sys.env.getOrElse("SAGA_BLIND_DB",    "saga-blind.db")
  val watchDir = sys.env.getOrElse("SAGA_BLIND_WATCH", "./definitions")

  override val host: String = sys.env.getOrElse("SAGA_BLIND_HOST", "0.0.0.0")
  override val port: Int    = sys.env.getOrElse("SAGA_BLIND_PORT", "7777").toInt

  val runtime = SagaRuntime(dbPath, watchDir)

  override def main(args: Array[String]): Unit =
    println(
      s"""
      |saga-blind starting
      |  db:       $dbPath
      |  watching: $watchDir
      |  http:     $host:$port
      """.stripMargin
    )
    runtime.start()
    super.main(args)
    Thread.currentThread.join()  // keep JVM alive — all other threads are daemon

  override def allRoutes: Seq[cask.Routes] = Seq(SagaRoutes(runtime))
