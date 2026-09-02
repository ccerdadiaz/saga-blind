package sagablind

import scala.compiletime.uninitialized

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import sagablind.core.*
import sagablind.store.{SqliteWalStore, SagaRow, StepRow}
import sagablind.pool.{OkvPool, OkvEntry}

import java.nio.file.{Files, Paths}

class SqliteWalStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  val dbPath = "/tmp/saga-blind-test.db"
  var store: SqliteWalStore = uninitialized

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(dbPath))
    store = SqliteWalStore(dbPath)
    store.init()

  override def afterEach(): Unit =
    store.close()
    Files.deleteIfExists(Paths.get(dbPath))

  // ── Saga ──────────────────────────────────────────────────────────────────

  "SqliteWalStore" should "insert and find a saga" in:
    val id = SagaId("goblin-42")
    store.insertSaga(id, "saga: goblin-campaign", SagaStatus.Running)
    val row = store.findSaga(id)
    row.isDefined shouldBe true
    row.get.status shouldBe SagaStatus.Running

  it should "update saga status" in:
    val id = SagaId("goblin-43")
    store.insertSaga(id, "saga: goblin-campaign", SagaStatus.Running)
    store.updateSagaStatus(id, SagaStatus.Done)
    store.findSaga(id).get.status shouldBe SagaStatus.Done

  it should "list all sagas" in:
    store.insertSaga(SagaId("s1"), "def1", SagaStatus.Running)
    store.insertSaga(SagaId("s2"), "def2", SagaStatus.Done)
    store.allSagas().size shouldBe 2

  it should "return None for unknown saga" in:
    store.findSaga(SagaId("ghost")) shouldBe None

  // ── Step ──────────────────────────────────────────────────────────────────

  it should "insert and retrieve steps" in:
    val id = SagaId("goblin-44")
    store.insertSaga(id, "def", SagaStatus.Running)
    store.insertStep(id, "smithy",  StepKind.Mandatory, StepStatus.Registered)
    store.insertStep(id, "boots",   StepKind.Mandatory, StepStatus.Registered)
    store.insertStep(id, "portrait",StepKind.Optional,  StepStatus.Registered)
    val steps = store.stepsFor(id)
    steps.size shouldBe 3
    steps.map(_.stepId) should contain allOf ("smithy", "boots", "portrait")

  it should "update step status" in:
    val id = SagaId("goblin-45")
    store.insertSaga(id, "def", SagaStatus.Running)
    store.insertStep(id, "smithy", StepKind.Mandatory, StepStatus.Registered)
    store.updateStepStatus(id, "smithy", StepStatus.Done)
    store.stepsFor(id).head.status shouldBe StepStatus.Done

  // ── Pool ──────────────────────────────────────────────────────────────────

  it should "deposit a pool entry" in:
    val id = SagaId("goblin-46")
    store.insertSaga(id, "def", SagaStatus.Running)
    val result = store.depositPoolEntry(id, "smithy", "weaponId", "\"W-042\"")
    result shouldBe Right(())
    val pool = store.poolFor(id)
    pool("weaponId")._1 shouldBe "smithy"
    pool("weaponId")._2 shouldBe "\"W-042\""

  it should "enforce ownership — reject duplicate key" in:
    val id = SagaId("goblin-47")
    store.insertSaga(id, "def", SagaStatus.Running)
    store.depositPoolEntry(id, "smithy", "weaponId", "\"W-042\"") shouldBe Right(())
    store.depositPoolEntry(id, "boots",  "weaponId", "\"W-999\"") shouldBe a[Left[?, ?]]
    // original value must survive
    store.poolFor(id)("weaponId")._1 shouldBe "smithy"

  it should "deposit a delta atomically" in:
    val id = SagaId("goblin-48")
    store.insertSaga(id, "def", SagaStatus.Running)
    val delta = Map(
      "weaponId" -> OkvEntry("smithy", ujson.Str("W-042")),
      "slotId"   -> OkvEntry("smithy", ujson.Num(99)),
    )
    store.depositDelta(id, delta) shouldBe Right(())
    val pool = store.poolFor(id)
    pool.size shouldBe 2
    pool("slotId")._1 shouldBe "smithy"

  it should "reject delta if any key is already owned" in:
    val id = SagaId("goblin-49")
    store.insertSaga(id, "def", SagaStatus.Running)
    store.depositPoolEntry(id, "__init__", "money", "42")
    val delta = Map(
      "newKey" -> OkvEntry("smithy", ujson.Str("ok")),
      "money"  -> OkvEntry("smithy", ujson.Num(99)),  // already owned by __init__
    )
    store.depositDelta(id, delta) shouldBe a[Left[?, ?]]
    // rollback — newKey must not exist
    store.poolFor(id).contains("newKey") shouldBe false

  it should "reconstruct full pool for a saga" in:
    val id = SagaId("goblin-50")
    store.insertSaga(id, "def", SagaStatus.Running)
    store.depositPoolEntry(id, "__init__", "money",    "42")
    store.depositPoolEntry(id, "smithy",   "weaponId", "\"W-042\"")
    store.depositPoolEntry(id, "boots",    "bootId",   "\"B-007\"")
    val pool = store.poolFor(id)
    pool.size shouldBe 3
    pool("money")._1    shouldBe "__init__"
    pool("weaponId")._1 shouldBe "smithy"
    pool("bootId")._1   shouldBe "boots"

  it should "isolate pools between different saga instances" in:
    val id1 = SagaId("goblin-51")
    val id2 = SagaId("goblin-52")
    store.insertSaga(id1, "def", SagaStatus.Running)
    store.insertSaga(id2, "def", SagaStatus.Running)
    store.depositPoolEntry(id1, "smithy", "weaponId", "\"W-001\"")
    store.depositPoolEntry(id2, "smithy", "weaponId", "\"W-002\"")  // same key, different saga
    store.poolFor(id1)("weaponId")._2 shouldBe "\"W-001\""
    store.poolFor(id2)("weaponId")._2 shouldBe "\"W-002\""
