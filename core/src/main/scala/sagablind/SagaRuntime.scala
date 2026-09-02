package sagablind

import sagablind.control.{FileWatcher, SagaControl, Saga}
import sagablind.core.*
import sagablind.loader.JarLoader
import sagablind.pool.PersistentOkvPool
import sagablind.store.{WalStore, SagaRow}

import java.nio.file.Paths
import scala.concurrent.duration.*

// ── SagaRuntime ──────────────────────────────────────────────────────────────
// The engine. Wires together all layers.
// Engine and ZombieHunter start and stop together as a pair.
//
// WalStore is injected — the engine does not know which implementation is used.
// SqliteWalStore lives in store-sqlite; the caller (Main) creates and injects it.
//
// Classloader lifecycle: jarLoader.release is called here after execute —
// SagaExecution has no knowledge of classloader management.
//
// TODO good-first-issue idiomatic-scala: replace jarLoader.release with
// scala.util.Using — URLClassLoader implements AutoCloseable

class SagaRuntime(
  store:       WalStore,
  watchDir:    String,
  stepTtl:     Duration = 30.seconds,
  zhScanEvery: Duration = 10.seconds,
  logger:      SagaLogger = SagaLogger.stdout,
):
  private val log         = logger.forComponent("runtime")
  private val jarLoader   = JarLoader(logger)
  private val sagaControl = SagaControl()
  private val executor    = SagaExecutor(store, sagaControl, logger)
  private val watcher     = FileWatcher(Paths.get(watchDir), sagaControl, logger)
  private val zh          = ZombieHunter(store, jarLoader, stepTtl, zhScanEvery, logger)

  private val inFlight: scala.collection.concurrent.TrieMap[String, Int] =
    scala.collection.concurrent.TrieMap.empty

  def start(): Unit =
    store.init()
    watcher.start()
    zh.start()
    log.info(s"started — watching=$watchDir")

  // ── Launch ────────────────────────────────────────────────────────────────

  def launch(sagaName: String, params: Map[String, ujson.Value]): Either[String, SagaId] =
    for
      saga      <- sagaControl.get(sagaName)
      _         <- Either.cond(
                     saga.status == DefinitionStatus.Playing,
                     (),
                     s"Saga '$sagaName' is ${saga.status} — not accepting new instances"
                   )
      sagaId     = SagaId.generate()
      providers <- jarLoader.load(sagaId, saga.definition.jarPath, saga.definition.steps.flatMap:
                     case SagaElement.Single(d)    => List(d)
                     case SagaElement.Parallel(ds) => ds
                   )
      pool       = PersistentOkvPool(sagaId, store)
      _         <- pool.init(params)
      _          = inFlight.updateWith(sagaName)(n => Some(n.getOrElse(0) + 1))
    yield
      val result = SagaContext.run(sagaId):
        executor.execute(sagaId, saga.definition, providers, pool)
      jarLoader.release(sagaId)
      inFlight.updateWith(sagaName)(n => Some(math.max(0, n.getOrElse(1) - 1)))
      result.map(_ => sagaId)
  .flatten

  // ── Control ───────────────────────────────────────────────────────────────

  def stop(name: String): Either[String, Unit]   = sagaControl.stop(name)
  def remove(name: String): Either[String, Unit] =
    sagaControl.remove(name, inFlight.getOrElse(name, 0))

  // ── Soft shutdown ─────────────────────────────────────────────────────────

  def shutdown(): Unit =
    log.info("shutdown initiated — stopping engine and ZombieHunter")
    sagaControl.stopAll()

    while inFlight.values.sum > 0 do
      log.info(s"waiting for ${inFlight.values.sum} in-flight instance(s)...")
      Thread.sleep(500)

    log.info("all instances finished — verifying WAL")

    val anomalies = store.allSagas().filter: row =>
      row.status match
        case SagaStatus.Done | SagaStatus.Compensated | SagaStatus.Failed => false
        case _ => true

    if anomalies.nonEmpty then
      log.warn(s"${anomalies.size} instance(s) not in terminal state:")
      anomalies.foreach(r => log.warn(s"  ${r.sagaId.value} → ${r.status}"))
    else
      log.info("WAL verified — all instances in terminal state")

    zh.stop()
    watcher.stop()
    store.close()
    log.info("shutdown complete")
    System.exit(0)

  // ── Query ─────────────────────────────────────────────────────────────────

  def list(): List[SagaRow]     = store.allSagas()
  def available(): List[String] = sagaControl.available
  def definitions(): List[Saga] = sagaControl.all
