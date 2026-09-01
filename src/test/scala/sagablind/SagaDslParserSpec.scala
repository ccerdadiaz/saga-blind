package sagablind

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import sagablind.core.*
import sagablind.dsl.SagaDslParser

class SagaDslParserSpec extends AnyFlatSpec with Matchers:

  def dsl(content: String): Either[String, SagaDefinition] =
    SagaDslParser.parse(content)

  def step(id: String, kind: String = "mandatory", inputs: List[ParamMapping] = Nil, compensate: List[ParamMapping] = Nil): StepDescriptor =
    StepDescriptor(id, StepKind.valueOf(kind.capitalize), id, inputs, compensate)

  // ── happy path ────────────────────────────────────────────────────────────

  "SagaDslParser" should "parse saga id and jar" in:
    val result = dsl("""
      |saga: goblin-campaign
      |jar: /libs/goblin.jar
      |steps:
      |  - id: measurements
      |    kind: mandatory
      |    class: com.goblin.Measurements
    """.stripMargin)
    result.isRight shouldBe true
    result.toOption.get.id      shouldBe "goblin-campaign"
    result.toOption.get.jarPath shouldBe "/libs/goblin.jar"

  it should "parse a mandatory step" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  - id: measurements
      |    kind: mandatory
      |    class: com.goblin.Measurements
    """.stripMargin).toOption.get
    saga.steps.size shouldBe 1
    saga.steps.head match
      case SagaElement.Single(d) =>
        d.id   shouldBe "measurements"
        d.kind shouldBe StepKind.Mandatory
      case other => fail(s"Expected Single, got $other")

  it should "parse an optional step" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  - id: portrait
      |    kind: optional
      |    class: com.goblin.Portrait
    """.stripMargin).toOption.get
    saga.steps.head match
      case SagaElement.Single(d) => d.kind shouldBe StepKind.Optional
      case other => fail(s"Expected Single, got $other")

  it should "parse a bestEffort step" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  - id: notification
      |    kind: bestEffort
      |    class: com.goblin.Notify
    """.stripMargin).toOption.get
    saga.steps.head match
      case SagaElement.Single(d) => d.kind shouldBe StepKind.BestEffort
      case other => fail(s"Expected Single, got $other")

  it should "parse input mappings" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  - id: getHat
      |    kind: mandatory
      |    class: com.goblin.HatService
      |    inputs:
      |      - param: goblinId
      |        from: __init__/goblinId
      |      - param: headPerimeter
      |        from: measurements/result.head
    """.stripMargin).toOption.get
    saga.steps.head match
      case SagaElement.Single(d) =>
        d.inputMappings.size shouldBe 2
        d.inputMappings.head shouldBe ParamMapping("goblinId", "__init__/goblinId")
        d.inputMappings(1)   shouldBe ParamMapping("headPerimeter", "measurements/result.head")
      case other => fail(s"Expected Single, got $other")

  it should "parse compensate mappings" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  - id: getHat
      |    kind: mandatory
      |    class: com.goblin.HatService
      |    inputs:
      |      - param: goblinId
      |        from: __init__/goblinId
      |    compensate:
      |      - param: hatSerialNumber
      |        from: getHat/output.serialNumber
    """.stripMargin).toOption.get
    saga.steps.head match
      case SagaElement.Single(d) =>
        d.compensateMappings.size shouldBe 1
        d.compensateMappings.head shouldBe ParamMapping("hatSerialNumber", "getHat/output.serialNumber")
      case other => fail(s"Expected Single, got $other")

  it should "parse a parallel block" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  - parallel:
      |    - id: smithy
      |      kind: mandatory
      |      class: com.goblin.Smithy
      |    - id: boots
      |      kind: mandatory
      |      class: com.goblin.Boots
    """.stripMargin).toOption.get
    saga.steps.head match
      case SagaElement.Parallel(steps) =>
        steps.map(_.id) shouldBe List("smithy", "boots")
      case other => fail(s"Expected Parallel, got $other")

  it should "parse mixed steps in order" in:
    val saga = dsl("""
      |saga: goblin-campaign
      |jar: /libs/goblin.jar
      |steps:
      |  - id: measurements
      |    kind: mandatory
      |    class: com.goblin.Measurements
      |  - parallel:
      |    - id: smithy
      |      kind: mandatory
      |      class: com.goblin.Smithy
      |    - id: boots
      |      kind: mandatory
      |      class: com.goblin.Boots
      |  - id: portrait
      |    kind: optional
      |    class: com.goblin.Portrait
    """.stripMargin).toOption.get
    saga.steps.size shouldBe 3
    saga.steps(0) shouldBe a[SagaElement.Single]
    saga.steps(1) shouldBe a[SagaElement.Parallel]
    saga.steps(2) shouldBe a[SagaElement.Single]

  it should "ignore comment lines" in:
    val saga = dsl("""
      |# goblin campaign
      |saga: goblin-campaign
      |# jar location
      |jar: /libs/goblin.jar
      |steps:
      |  # first step
      |  - id: measurements
      |    kind: mandatory
      |    class: com.goblin.Measurements
    """.stripMargin).toOption.get
    saga.id shouldBe "goblin-campaign"
    saga.steps.size shouldBe 1

  // ── error cases ───────────────────────────────────────────────────────────

  it should "return Left if saga header is missing" in:
    dsl("""
      |jar: /libs/goblin.jar
      |steps:
      |  - id: measurements
      |    kind: mandatory
      |    class: com.goblin.Measurements
    """.stripMargin).swap.getOrElse("") should include("saga")

  it should "return Left if jar header is missing" in:
    dsl("""
      |saga: goblin-campaign
      |steps:
      |  - id: measurements
      |    kind: mandatory
      |    class: com.goblin.Measurements
    """.stripMargin).swap.getOrElse("") should include("jar")

  it should "return Left if steps section is missing" in:
    dsl("""
      |saga: goblin-campaign
      |jar: /libs/goblin.jar
    """.stripMargin).swap.getOrElse("") should include("steps")

  it should "return Left if steps section is empty" in:
    dsl("""
      |saga: goblin-campaign
      |jar: /libs/goblin.jar
      |steps:
    """.stripMargin) shouldBe a[Left[?, ?]]
