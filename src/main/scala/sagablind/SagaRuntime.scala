package sagablind

import sagablind.control.{FileWatcher, SagaRegistry, SagaServiceRegistry, RegistryEntry}
import sagablind.core.*
import sagablind.loader.JarLoader
import sagablind.pool.PersistentOkvPool
import sagablind.store.{WalStore, SagaRow}

import java.nio.file.Paths

// ── SagaRuntime ──────────────────────────────────────────────────────────────
// The engine. Wires together all layers.
// Exposes launch() and control operations (pause/continue/stop/remove).

class SagaRuntime(dbPath: String, watchDir: String):

  private val store      = WalStore(dbPath)
  private val jarLoader  = JarLoader()
  private val serviceReg = SagaServiceRegistry()
  private val executor   = SagaExecutor(store, jarLoader, serviceReg)
  private val watcher    = FileWatcher(Paths.get(watchDir), serviceReg)

  // tracks running instance count per definition name
  private val inFlight: scala.collection.concurrent.TrieMap[String, Int] =
    scala.collection.concurrent.TrieMap.empty

  def start(): Unit =
    store.init()
    watcher.start()
    println(s"[SagaRuntime] started — db=$dbPath watching=$watchDir")

  def stop(): Unit =
    watcher.stop()
    store.close()
    println("[SagaRuntime] stopped")

  // ── Launch ────────────────────────────────────────────────────────────────

  def launch(sagaName: String, params: Map[String, ujson.Value]): Either[String, SagaId] =
    for
      definition <- serviceReg.resolve(sagaName)
      sagaId      = SagaId.generate()
      providers  <- jarLoader.load(sagaId, definition.jarPath, definition.steps.flatMap:
                      case SagaElement.Single(d)    => List(d)
                      case SagaElement.Parallel(ds) => ds
                    )
      pool        = PersistentOkvPool(sagaId, store)
      _          <- pool.init(params)
      _           = inFlight.updateWith(sagaName)(n => Some(n.getOrElse(0) + 1))
      _          <- executor.execute(sagaId, definition, providers, pool)
      _           = inFlight.updateWith(sagaName)(n => Some(math.max(0, n.getOrElse(1) - 1)))
    yield sagaId

  // ── Control ───────────────────────────────────────────────────────────────

  def pause(name: String): Either[String, Unit]    = serviceReg.pause(name)
  def continue(name: String): Either[String, Unit] = serviceReg.continue(name)
  def stop(name: String): Either[String, Unit]     = serviceReg.stop(name)

  def remove(name: String): Either[String, Unit] =
    serviceReg.remove(name, inFlight.getOrElse(name, 0))

  // ── Query ─────────────────────────────────────────────────────────────────

  def list(): List[SagaRow]          = store.allSagas()
  def available(): List[String]      = serviceReg.available
  def definitions(): List[RegistryEntry] = serviceReg.all
