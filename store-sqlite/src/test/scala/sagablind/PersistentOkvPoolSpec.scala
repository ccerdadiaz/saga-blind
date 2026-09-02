package sagablind

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import sagablind.core.SagaId
import sagablind.pool.PersistentOkvPool
import sagablind.store.SqliteWalStore

import java.nio.file.{Files, Paths}

class PersistentOkvPoolSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  val dbPath = "/tmp/saga-blind-pool-test.db"
  var store: SqliteWalStore = scala.compiletime.uninitialized
  var pool: PersistentOkvPool = scala.compiletime.uninitialized
  val sagaId = SagaId("goblin-pool-test")

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(dbPath))
    store = SqliteWalStore(dbPath)
    store.init()
    store.insertSaga(sagaId, "def", sagablind.core.SagaStatus.Running)
    pool = PersistentOkvPool(sagaId, store)

  override def afterEach(): Unit =
    store.close()
    Files.deleteIfExists(Paths.get(dbPath))

  // ── deposit ───────────────────────────────────────────────────────────────

  "PersistentOkvPool" should "deposit a key and read it from memory" in:
    pool.deposit("smithy", "weaponId", ujson.Str("W-042")) shouldBe Right(())
    pool.get("weaponId") shouldBe Some(ujson.Str("W-042"))

  it should "persist the key to WAL on deposit" in:
    pool.deposit("smithy", "weaponId", ujson.Str("W-042"))
    val walPool = store.poolFor(sagaId)
    walPool.contains("weaponId") shouldBe true
    walPool("weaponId")._1 shouldBe "smithy"

  it should "enforce ownership on deposit" in:
    pool.deposit("smithy", "weaponId", ujson.Str("W-042")) shouldBe Right(())
    pool.deposit("boots",  "weaponId", ujson.Str("W-999")) shouldBe a[Left[?, ?]]
    pool.get("weaponId") shouldBe Some(ujson.Str("W-042"))

  // ── depositDelta ──────────────────────────────────────────────────────────

  it should "deposit a delta atomically" in:
    val entries = Map(
      "weaponId" -> ujson.Str("W-042"),
      "slotId"   -> ujson.Num(99),
    )
    pool.depositDelta("smithy", entries) shouldBe Right(())
    pool.get("weaponId") shouldBe Some(ujson.Str("W-042"))
    pool.get("slotId")   shouldBe Some(ujson.Num(99))

  it should "reject delta and not pollute memory if WAL rejects it" in:
    pool.deposit("__init__", "money", ujson.Num(42))
    val entries = Map(
      "newKey" -> ujson.Str("ok"),
      "money"  -> ujson.Num(99),  // already owned
    )
    pool.depositDelta("smithy", entries) shouldBe a[Left[?, ?]]
    pool.get("newKey") shouldBe None  // memory must not have it

  // ── init ──────────────────────────────────────────────────────────────────

  it should "inject initial params with __init__ owner" in:
    val params = Map(
      "money"      -> ujson.Num(42),
      "customerId" -> ujson.Str("uuid-abc"),
    )
    pool.init(params) shouldBe Right(())
    pool.get("money")      shouldBe Some(ujson.Num(42))
    pool.get("customerId") shouldBe Some(ujson.Str("uuid-abc"))
    store.poolFor(sagaId)("money")._1 shouldBe "__init__"

  // ── restore ───────────────────────────────────────────────────────────────

  it should "restore memory from WAL after simulated crash" in:
    pool.deposit("__init__", "money",    ujson.Num(42))
    pool.deposit("smithy",   "weaponId", ujson.Str("W-042"))
    pool.deposit("boots",    "bootId",   ujson.Str("B-007"))

    // simulate crash — new pool instance, same WAL
    val restoredPool = PersistentOkvPool(sagaId, store)
    restoredPool.restore() shouldBe Right(())

    restoredPool.get("money")    shouldBe Some(ujson.Num(42))
    restoredPool.get("weaponId") shouldBe Some(ujson.Str("W-042"))
    restoredPool.get("bootId")   shouldBe Some(ujson.Str("B-007"))

  it should "restore complex JSON values correctly" in:
    val candidates = ujson.Arr(
      ujson.Obj("marca" -> ujson.Str("toyota"), "modelo" -> ujson.Str("prius")),
      ujson.Obj("marca" -> ujson.Str("opel"),   "modelo" -> ujson.Str("insignia")),
    )
    pool.deposit("serviceA", "candidates", candidates)

    val restoredPool = PersistentOkvPool(sagaId, store)
    restoredPool.restore() shouldBe Right(())
    restoredPool.get("candidates") shouldBe Some(candidates)

  it should "restore ownership correctly" in:
    pool.deposit("__init__", "money",    ujson.Num(42))
    pool.deposit("smithy",   "weaponId", ujson.Str("W-042"))

    val restoredPool = PersistentOkvPool(sagaId, store)
    restoredPool.restore()

    // ownership enforced after restore — boots cannot claim weaponId
    restoredPool.deposit("boots", "weaponId", ujson.Str("W-999")) shouldBe a[Left[?, ?]]

  // ── get ───────────────────────────────────────────────────────────────────

  it should "return None for unknown key" in:
    pool.get("ghost") shouldBe None

  it should "support nested JSON objects" in:
    val weapon = ujson.Obj(
      "id"     -> ujson.Str("W-042"),
      "damage" -> ujson.Num(15.5),
      "tags"   -> ujson.Arr(ujson.Str("fire"), ujson.Str("magic")),
    )
    pool.deposit("smithy", "weapon", weapon) shouldBe Right(())
    pool.get("weapon") shouldBe Some(weapon)
