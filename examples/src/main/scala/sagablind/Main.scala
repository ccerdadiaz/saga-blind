package sagablind

import sagablind.store.SqliteWalStore

object SagaServer extends cask.Main:
  val dbPath   = sys.env.getOrElse("SAGA_BLIND_DB",    "saga-blind.db")
  val watchDir = sys.env.getOrElse("SAGA_BLIND_WATCH", "./definitions")
  override val host = sys.env.getOrElse("SAGA_BLIND_HOST", "0.0.0.0")
  override val port = sys.env.getOrElse("SAGA_BLIND_PORT", "7777").toInt

  val store   = SqliteWalStore(dbPath)
  val runtime = SagaRuntime(store, watchDir)

  override def main(args: Array[String]): Unit =
    println(s"""
      |saga-blind starting
      |  db:       $dbPath
      |  watching: $watchDir
      |  http:     $host:$port
      """.stripMargin)
    runtime.start()
    super.main(args)
    Thread.currentThread.join()

  override def allRoutes: Seq[cask.Routes] = Seq(SagaRoutes(runtime))
