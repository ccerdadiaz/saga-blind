package sagablind

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import sagablind.control.{SagaServiceRegistry, SagaRegistry}
import sagablind.core.*
import sagablind.loader.{JarLoader, SagaStepProvider}
import sagablind.pool.PersistentOkvPool
import sagablind.store.WalStore
import sagablind.core.{SagaStatus, StepStatus}

import java.nio.file.{Files, Paths}

class SagaExecutorSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  val dbPath = "/tmp/saga-blind-executor-test.db"
  var store:    WalStore            = scala.compiletime.uninitialized
  var loader:   JarLoader           = scala.compiletime.uninitialized
  var registry: SagaServiceRegistry = scala.compiletime.uninitialized
  var executor: SagaExecutor        = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    Files.deleteIfExists(Paths.get(dbPath))
    store    = WalStore(dbPath)
    store.init()
    loader   = JarLoader()
    registry = SagaServiceRegistry()
    executor = SagaExecutor(store, loader, registry)

  override def afterEach(): Unit =
    store.close()
    Files.deleteIfExists(Paths.get(dbPath))

  // ── helpers ───────────────────────────────────────────────────────────────

  def makePool(sagaId: SagaId, params: Map[String, ujson.Value]): PersistentOkvPool =
    val pool = PersistentOkvPool(sagaId, store)
    pool.init(params)
    pool

  def descriptor(id: String, kind: StepKind = StepKind.Mandatory,
    inputs: List[ParamMapping] = Nil, compensate: List[ParamMapping] = Nil): StepDescriptor =
    StepDescriptor(id, kind, s"com.goblin.$id", inputs, compensate)

  def definition(id: String, steps: List[SagaElement]): SagaDefinition =
    SagaDefinition(id, "/fake/jar.jar", steps)

  def depositingProvider(id: String, outputs: Map[String, ujson.Value]): SagaStepProvider =
    new SagaStepProvider:
      def stepId = id
      def execute(args: Map[String, ujson.Value]) = Right(outputs)
      def compensate(args: Map[String, ujson.Value]) = Right(())

  class TrackingProvider(val stepId: String, outputs: Map[String, ujson.Value]) extends SagaStepProvider:
    var compensated = false
    var compensateArgs: Map[String, ujson.Value] = Map.empty
    def execute(args: Map[String, ujson.Value]) = Right(outputs)
    def compensate(args: Map[String, ujson.Value]) =
      compensated = true
      compensateArgs = args
      Right(())

  def failingProvider(id: String): SagaStepProvider =
    new SagaStepProvider:
      def stepId = id
      def execute(args: Map[String, ujson.Value]) = Left(RuntimeException("step failed"))
      def compensate(args: Map[String, ujson.Value]) = Right(())

  // ── happy path ────────────────────────────────────────────────────────────

  "SagaExecutor" should "execute a single mandatory step and mark saga Done" in:
    val sagaId = SagaId("exec-1")
    val pool   = makePool(sagaId, Map("goblinId" -> ujson.Str("G-042")))
    val desc   = descriptor("measurements")
    val defn   = definition("test", List(SagaElement.Single(desc)))
    val providers = Map("measurements" -> depositingProvider("measurements",
      Map("head" -> ujson.Num(58))))

    executor.execute(sagaId, defn, providers, pool) shouldBe Right(())
    store.findSaga(sagaId).get.status shouldBe SagaStatus.Done
    store.stepsFor(sagaId).head.status shouldBe StepStatus.Done

  it should "deposit step outputs into the pool" in:
    val sagaId = SagaId("exec-2")
    val pool   = makePool(sagaId, Map("goblinId" -> ujson.Str("G-042")))
    val desc   = descriptor("measurements")
    val defn   = definition("test", List(SagaElement.Single(desc)))
    val providers = Map("measurements" -> depositingProvider("measurements",
      Map("head" -> ujson.Num(58), "armLength" -> ujson.Num(42))))

    executor.execute(sagaId, defn, providers, pool)
    pool.memory.getByOwner("measurements", "head")      shouldBe Some(ujson.Num(58))
    pool.memory.getByOwner("measurements", "armLength") shouldBe Some(ujson.Num(42))

  it should "resolve input mappings from pool before calling execute" in:
    val sagaId = SagaId("exec-3")
    val pool   = makePool(sagaId, Map("goblinId" -> ujson.Str("G-042")))
    var receivedArgs: Map[String, ujson.Value] = Map.empty

    val provider = new SagaStepProvider:
      def stepId = "getHat"
      def execute(args: Map[String, ujson.Value]) =
        receivedArgs = args
        Right(Map.empty)
      def compensate(args: Map[String, ujson.Value]) = Right(())

    val desc = descriptor("getHat",
      inputs = List(ParamMapping("goblinId", "__init__/goblinId")))
    val defn = definition("test", List(SagaElement.Single(desc)))

    executor.execute(sagaId, defn, Map("getHat" -> provider), pool)
    receivedArgs("goblinId") shouldBe ujson.Str("G-042")

  it should "chain outputs from one step as inputs to the next" in:
    val sagaId = SagaId("exec-4")
    val pool   = makePool(sagaId, Map("goblinId" -> ujson.Str("G-042")))
    var hatArgs: Map[String, ujson.Value] = Map.empty

    val measurements = depositingProvider("measurements", Map("head" -> ujson.Num(58)))

    val getHat = new SagaStepProvider:
      def stepId = "getHat"
      def execute(args: Map[String, ujson.Value]) =
        hatArgs = args
        Right(Map.empty)
      def compensate(args: Map[String, ujson.Value]) = Right(())

    val steps = List(
      SagaElement.Single(descriptor("measurements")),
      SagaElement.Single(descriptor("getHat",
        inputs = List(ParamMapping("headPerimeter", "measurements/head")))),
    )
    val defn = definition("test", steps)

    executor.execute(sagaId, defn, Map("measurements" -> measurements, "getHat" -> getHat), pool)
    hatArgs("headPerimeter") shouldBe ujson.Num(58)

  it should "execute parallel steps concurrently" in:
    val sagaId = SagaId("exec-5")
    val pool   = makePool(sagaId, Map.empty)
    val smithy = depositingProvider("smithy", Map("weaponId" -> ujson.Str("W-042")))
    val boots  = depositingProvider("boots",  Map("bootId"   -> ujson.Str("B-007")))

    val defn = definition("test", List(
      SagaElement.Parallel(List(descriptor("smithy"), descriptor("boots")))
    ))

    executor.execute(sagaId, defn, Map("smithy" -> smithy, "boots" -> boots), pool) shouldBe Right(())
    pool.memory.getByOwner("smithy", "weaponId") shouldBe Some(ujson.Str("W-042"))
    pool.memory.getByOwner("boots",  "bootId")   shouldBe Some(ujson.Str("B-007"))

  it should "skip optional step on failure and continue" in:
    val sagaId = SagaId("exec-6")
    val pool   = makePool(sagaId, Map.empty)
    val defn   = definition("test", List(
      SagaElement.Single(descriptor("portrait", kind = StepKind.Optional))
    ))
    executor.execute(sagaId, defn, Map("portrait" -> failingProvider("portrait")), pool) shouldBe Right(())
    store.findSaga(sagaId).get.status shouldBe SagaStatus.Done

  it should "skip bestEffort step on failure and continue" in:
    val sagaId = SagaId("exec-7")
    val pool   = makePool(sagaId, Map.empty)
    val defn   = definition("test", List(
      SagaElement.Single(descriptor("notification", kind = StepKind.BestEffort))
    ))
    executor.execute(sagaId, defn, Map("notification" -> failingProvider("notification")), pool) shouldBe Right(())
    store.findSaga(sagaId).get.status shouldBe SagaStatus.Done

  // ── compensation ──────────────────────────────────────────────────────────

  it should "compensate executed steps LIFO when mandatory step fails" in:
    val sagaId = SagaId("exec-8")
    val pool   = makePool(sagaId, Map.empty)

    val stepA = TrackingProvider("stepA", Map("aOut" -> ujson.Str("A-001")))
    val stepB = TrackingProvider("stepB", Map("bOut" -> ujson.Str("B-002")))
    val stepC = failingProvider("stepC")

    val defn = definition("test", List(
      SagaElement.Single(descriptor("stepA")),
      SagaElement.Single(descriptor("stepB")),
      SagaElement.Single(descriptor("stepC")),
    ))

    executor.execute(sagaId, defn, Map("stepA" -> stepA, "stepB" -> stepB, "stepC" -> stepC), pool)

    stepB.compensated shouldBe true
    stepA.compensated shouldBe true
    store.findSaga(sagaId).get.status shouldBe SagaStatus.Compensated

  it should "pass compensation args resolved from pool" in:
    val sagaId = SagaId("exec-9")
    val pool   = makePool(sagaId, Map("startId" -> ujson.Str("S-001")))

    val stepA = TrackingProvider("stepA", Map("aResult" -> ujson.Str("A-result")))
    val stepB = failingProvider("stepB")

    val descA = StepDescriptor(
      id                 = "stepA",
      kind               = StepKind.Mandatory,
      className          = "com.goblin.stepA",
      inputMappings      = Nil,
      compensateMappings = List(
        ParamMapping("originalId", "__init__/startId"),
        ParamMapping("resultId",   "stepA/aResult"),
      ),
    )
    val descB = StepDescriptor("stepB", StepKind.Mandatory, "com.goblin.stepB", Nil, Nil)

    val defn = definition("test", List(
      SagaElement.Single(descA),
      SagaElement.Single(descB),
    ))

    val result = executor.execute(sagaId, defn, Map("stepA" -> stepA, "stepB" -> stepB), pool)

    result shouldBe a[Left[?, ?]]
    stepA.compensated shouldBe true
    stepA.compensateArgs("originalId") shouldBe ujson.Str("S-001")
    stepA.compensateArgs("resultId")   shouldBe ujson.Str("A-result")

  // ── validation ────────────────────────────────────────────────────────────

  it should "reject a definition with forward owner references" in:
    val sagaId = SagaId("exec-10")
    val pool   = makePool(sagaId, Map.empty)

    val defn = definition("test", List(
      SagaElement.Single(descriptor("stepA",
        inputs = List(ParamMapping("x", "stepB/someKey"))
      )),
      SagaElement.Single(descriptor("stepB")),
    ))

    executor.execute(sagaId, defn, Map.empty, pool) shouldBe a[Left[?, ?]]
