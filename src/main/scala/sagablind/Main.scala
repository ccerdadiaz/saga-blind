package sagablind

// ── Main ─────────────────────────────────────────────────────────────────────
// Entry point for saga-blind.
// Configuration via environment variables — no config file needed.
//
//   SAGA_BLIND_DB       path to SQLite DB       (default: saga-blind.db)
//   SAGA_BLIND_WATCH    path to watched dir     (default: ./definitions)
//   SAGA_BLIND_HOST     HTTP bind host          (default: 0.0.0.0)
//   SAGA_BLIND_PORT     HTTP port               (default: 7777)

@main def main(): Unit =
  val dbPath   = sys.env.getOrElse("SAGA_BLIND_DB",    "saga-blind.db")
  val watchDir = sys.env.getOrElse("SAGA_BLIND_WATCH", "./definitions")
  val host     = sys.env.getOrElse("SAGA_BLIND_HOST",  "0.0.0.0")
  val port     = sys.env.getOrElse("SAGA_BLIND_PORT",  "7777").toInt

  println(
    s"""
    |saga-blind starting
    |  db:       $dbPath
    |  watching: $watchDir
    |  http:     $host:$port
    """.stripMargin
  )

  SagaServer(dbPath, watchDir, host, port)
