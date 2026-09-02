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
// Classloader lifecycle: jarLoader.release is called here after execute —
// SagaExecutor has no knowledge of classloader management.
//
// TODO good-first-issue idiomatic-scala: replace jarLoader.release with
// scala.util.Using — URLClassLoader implements AutoCloseable

class SagaRuntime(
  dbPath:      String,
  watchDir:    String,
  stepTtl:     Duration = 30.seconds,
  zhScanEvery: Duration = 10.seconds,
):
  private val store       = WalStore(dbPath)
  private val jarLoader   = JarLoader()
  private val sagaControl = SagaControl()
  private val executor    = SagaExecutor(store, sagaControl)
  private val watcher     = FileWatcher(Paths.get(watchDir), sagaControl)
  private val zh          = ZombieHunter(store, jarLoader, stepTtl, zhScanEvery)

  private val inFlight: scala.collection.concurrent.TrieMap[String, Int] =
    scala.collection.concurrent.TrieMap.empty

  def start(): Unit =
    store.init()
    watcher.start()
    zh.start()
    println(s"[SagaRuntime] started — db=$dbPath watching=$watchDir")

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
      // execute outside the for — release and inFlight update happen always,
      // regardless of whether execute succeeds or fails
      val result = executor.execute(sagaId, saga.definition, providers, pool)
      jarLoader.release(sagaId)
      inFlight.updateWith(sagaName)(n => Some(math.max(0, n.getOrElse(1) - 1)))
      result.map(_ => sagaId)
  .flatten

  // ── Control ───────────────────────────────────────────────────────────────

  def pause(name: String): Either[String, Unit]    = sagaControl.pause(name)
  def continue(name: String): Either[String, Unit] = sagaControl.continue(name)
  def stop(name: String): Either[String, Unit]     = sagaControl.stop(name)

  def remove(name: String): Either[String, Unit] =
    sagaControl.remove(name, inFlight.getOrElse(name, 0))

  // ── Soft shutdown ─────────────────────────────────────────────────────────

  def shutdown(): Unit =
    println("[SagaRuntime] shutdown initiated — stopping engine and ZombieHunter")

    sagaControl.stopAll()

    while inFlight.values.sum > 0 do
      println(s"[SagaRuntime] waiting for ${inFlight.values.sum} in-flight instance(s)...")
      Thread.sleep(500)

    println("[SagaRuntime] all instances finished — verifying WAL")

    val anomalies = store.allSagas().filter: row =>
      row.status match
        case SagaStatus.Done | SagaStatus.Compensated | SagaStatus.Failed => false
        case _ => true

    if anomalies.nonEmpty then
      println(s"[SagaRuntime] WARNING — ${anomalies.size} instance(s) not in terminal state:")
      anomalies.foreach(r => println(s"  ${r.sagaId.value} → ${r.status}"))
    else
      println("[SagaRuntime] WAL verified — all instances in terminal state")

    zh.stop()
    watcher.stop()
    store.close()
    println("[SagaRuntime] shutdown complete")
    System.exit(0)

  // ── Query ─────────────────────────────────────────────────────────────────

  def list(): List[SagaRow]     = store.allSagas()
  def available(): List[String] = sagaControl.available
  def definitions(): List[Saga] = sagaControl.all
