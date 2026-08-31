package sagablind

import sagablind.control.{FileWatcher, SagaRegistry, SagaServiceRegistry}
import sagablind.core.*
import sagablind.loader.JarLoader
import sagablind.pool.PersistentOkvPool
import sagablind.store.{WalStore, SagaRow}

import java.nio.file.Paths

// ── SagaRuntime ──────────────────────────────────────────────────────────────
// The engine. Wires together all layers.
// Exposes launch() as the single entry point for saga instantiation.
// The HTTP server calls launch() — nothing else does.

class SagaRuntime(
  dbPath:      String,
  watchDir:    String,
):
  private val store       = WalStore(dbPath)
  private val jarLoader   = JarLoader()
  private val registry    = SagaRegistry()
  private val serviceReg  = SagaServiceRegistry()
  private val executor    = SagaExecutor(store, jarLoader)
  private val fileWatcher = FileWatcher(Paths.get(watchDir), serviceReg)

  def start(): Unit =
    store.init()
    fileWatcher.start()
    println(s"[SagaRuntime] started — db=$dbPath watching=$watchDir")

  def stop(): Unit =
    fileWatcher.stop()
    store.close()
    println("[SagaRuntime] stopped")

  /** Launch a new saga instance.
   *  Called by the HTTP server on POST /sagas/launch.
   *  Returns the sagaId of the new instance, or an error. */
  def launch(
    sagaName: String,
    params:   Map[String, ujson.Value],
  ): Either[String, SagaId] =
    for
      definition <- serviceReg.resolve(sagaName)
      sagaId      = SagaId.generate()
      providers  <- jarLoader.load(sagaId, definition.jarPath, definition.steps.flatMap:
                      case SagaElement.Single(d)   => List(d)
                      case SagaElement.Parallel(ds) => ds
                    )
      pool        = PersistentOkvPool(sagaId, store)
      _          <- pool.init(params)
      _          <- executor.execute(sagaId, definition, providers, pool)
    yield sagaId

  /** List all saga instances in the WAL. */
  def list(): List[SagaRow] = store.allSagas()

  /** Available saga definitions in the service registry. */
  def available(): List[String] = serviceReg.available
