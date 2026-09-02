package sagablind

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import sagablind.core.*
import sagablind.loader.JarLoader
import sagablind.store.SqliteWalStore
import sagablind.core.{SagaStatus, StepStatus}

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

class ZombieHunterSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  val dbPath = "/tmp/saga-blind-zh-test.db"
  var store: SqliteWalStore  = scala.compiletime.uninitialized
  var loader: JarLoader = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(dbPath))
    store  = SqliteWalStore(dbPath)
    store.init()
    loader = JarLoader()

  override def afterEach(): Unit =
    store.close()
    Files.deleteIfExists(Paths.get(dbPath))

  // ── helpers ───────────────────────────────────────────────────────────────

  val dslContent =
    """saga: goblin-campaign
      |jar: /fake/jar.jar
      |steps:
      |  - id: measurements
      |    kind: mandatory
      |    class: com.goblin.Measurements
    """.stripMargin

  val pastTime = "2000-01-01T00:00:00Z"

  /** A SagaLogger that captures error messages for test assertions */
  class CapturingLogger extends SagaLogger:
    val errors = scala.collection.mutable.ListBuffer.empty[String]
    def debug(component: String, msg: => String): Unit = ()
    def info(component: String, msg: => String):  Unit = ()
    def warn(component: String, msg: => String):  Unit = ()
    def error(component: String, msg: => String): Unit = errors += msg

  def makeZH(ttl: Duration = 100.millis, scan: Duration = 50.millis,
    logger: SagaLogger = SagaLogger.noOp): ZombieHunter =
    ZombieHunter(store, loader, stepTtl = ttl, scanEvery = scan, logger = logger)

  def insertRunning(sagaId: SagaId): Unit =
    store.insertSaga(sagaId, dslContent, SagaStatus.Running)

  // ── TTL detection ─────────────────────────────────────────────────────────

  "ZombieHunter" should "mark a Registered step as Unknown when TTL exceeded" in:
    val sagaId = SagaId("zh-1")
    insertRunning(sagaId)
    store.insertStepAt(sagaId, "measurements", StepKind.Mandatory, StepStatus.Registered, pastTime)

    val zh = makeZH()
    zh.start()
    Thread.sleep(300)
    zh.stop()

    store.stepsFor(sagaId).head.status shouldBe StepStatus.Unknown

  it should "not mark a Registered step as Unknown when TTL not exceeded" in:
    val sagaId     = SagaId("zh-2")
    val recentTime = java.time.Instant.now().toString
    insertRunning(sagaId)
    store.insertStepAt(sagaId, "measurements", StepKind.Mandatory, StepStatus.Registered, recentTime)

    val zh = makeZH(ttl = 60.seconds)
    zh.start()
    Thread.sleep(300)
    zh.stop()

    store.stepsFor(sagaId).head.status shouldBe StepStatus.Registered

  it should "not touch Done steps" in:
    val sagaId = SagaId("zh-3")
    insertRunning(sagaId)
    store.insertStepAt(sagaId, "measurements", StepKind.Mandatory, StepStatus.Done, pastTime)

    val zh = makeZH()
    zh.start()
    Thread.sleep(300)
    zh.stop()

    store.stepsFor(sagaId).head.status shouldBe StepStatus.Done

  it should "not touch sagas not in Running state" in:
    val sagaId = SagaId("zh-4")
    store.insertSaga(sagaId, dslContent, SagaStatus.Stopped)
    store.insertStepAt(sagaId, "measurements", StepKind.Mandatory, StepStatus.Registered, pastTime)

    val zh = makeZH()
    zh.start()
    Thread.sleep(300)
    zh.stop()

    store.stepsFor(sagaId).head.status shouldBe StepStatus.Registered

  // ── Recovery with unavailable jar ─────────────────────────────────────────

  it should "log error when jar cannot be loaded for recovery" in:
    val sagaId  = SagaId("zh-5")
    val capture = CapturingLogger()
    insertRunning(sagaId)
    store.insertStepAt(sagaId, "measurements", StepKind.Mandatory, StepStatus.Registered, pastTime)

    val zh = makeZH(logger = capture)
    zh.start()
    Thread.sleep(500)
    zh.stop()

    capture.errors.exists(_.contains("Cannot load jar")) shouldBe true
