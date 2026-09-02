package sagablind

import sagablind.store.SqliteWalStore

// ── GoblinWorld ───────────────────────────────────────────────────────────────
// saga-blind runtime for the goblin-world demo.
// Injects SqliteWalStore and Slf4jLogger into SagaRuntime.
//
// Configuration via environment variables:
//   SAGA_BLIND_DB       SQLite DB path     (default: goblin-world.db)
//   SAGA_BLIND_WATCH    definitions dir    (default: ./definitions)
//   SAGA_BLIND_HOST     HTTP bind host     (default: 0.0.0.0)
//   SAGA_BLIND_PORT     HTTP port          (default: 7777)

object GoblinWorld extends cask.Main:

  val dbPath   = sys.env.getOrElse("SAGA_BLIND_DB",    "goblin-world.db")
  val watchDir = sys.env.getOrElse("SAGA_BLIND_WATCH", "./definitions")

  override val host = sys.env.getOrElse("SAGA_BLIND_HOST", "0.0.0.0")
  override val port = sys.env.getOrElse("SAGA_BLIND_PORT", "7777").toInt

  val logger  = Slf4jLogger("sagablind")
  val store   = SqliteWalStore(dbPath)
  val runtime = SagaRuntime(store, watchDir, logger = logger)

  override def main(args: Array[String]): Unit =
    println(
      s"""
         |  ██████╗  ██████╗ ██████╗ ██╗     ██╗███╗   ██╗    ██╗    ██╗ ██████╗ ██████╗ ██╗     ██████╗
         |  ██╔════╝ ██╔═══██╗██╔══██╗██║     ██║████╗  ██║    ██║    ██║██╔═══██╗██╔══██╗██║     ██╔══██╗
         |  ██║  ███╗██║   ██║██████╔╝██║     ██║██╔██╗ ██║    ██║ █╗ ██║██║   ██║██████╔╝██║     ██║  ██║
         |  ██║   ██║██║   ██║██╔══██╗██║     ██║██║╚██╗██║    ██║███╗██║██║   ██║██╔══██╗██║     ██║  ██║
         |  ╚██████╔╝╚██████╔╝██████╔╝███████╗██║██║ ╚████║    ╚███╔███╔╝╚██████╔╝██║  ██║███████╗██████╔╝
         |   ╚═════╝  ╚═════╝ ╚═════╝ ╚══════╝╚═╝╚═╝  ╚═══╝     ╚══╝╚══╝  ╚═════╝ ╚═╝  ╚═╝╚══════╝╚═════╝
         |
         |  saga-blind runtime — goblin-world edition
         |  db:       $dbPath
         |  watching: $watchDir
         |  http:     $host:$port
         |
      """.stripMargin
    )
    runtime.start()
    super.main(args)
    Thread.currentThread.join()

  override def allRoutes: Seq[cask.Routes] = Seq(SagaRoutes(runtime))
