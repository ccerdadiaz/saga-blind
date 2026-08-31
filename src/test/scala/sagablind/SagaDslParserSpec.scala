package sagablind

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import sagablind.core.*
import sagablind.dsl.SagaDslParser

class SagaDslParserSpec extends AnyFlatSpec with Matchers:

  def dsl(content: String): Either[String, SagaDefinition] =
    SagaDslParser.parse(content)

  // ── happy path ────────────────────────────────────────────────────────────

  "SagaDslParser" should "parse a complete saga definition" in:
    val result = dsl("""
      |saga: goblin-campaign
      |jar: /libs/goblin-services.jar
      |
      |steps:
      |  mandatory: measurements
      |  parallel:
      |    - smithy
      |    - boots
      |  optional: portrait
      |  bestEffort: notification
    """.stripMargin)
    result.isRight shouldBe true
    val saga = result.toOption.get
    saga.id      shouldBe "goblin-campaign"
    saga.jarPath shouldBe "/libs/goblin-services.jar"
    saga.steps.size shouldBe 4

  it should "parse mandatory step" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  mandatory: measurements
    """.stripMargin).toOption.get
    saga.steps.head shouldBe SagaElement.Single(
      StepDescriptor("measurements", StepKind.Mandatory, "measurements", Nil)
    )

  it should "parse optional step" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  optional: portrait
    """.stripMargin).toOption.get
    saga.steps.head shouldBe SagaElement.Single(
      StepDescriptor("portrait", StepKind.Optional, "portrait", Nil)
    )

  it should "parse bestEffort step" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  bestEffort: notification
    """.stripMargin).toOption.get
    saga.steps.head shouldBe SagaElement.Single(
      StepDescriptor("notification", StepKind.BestEffort, "notification", Nil)
    )

  it should "parse parallel block with multiple steps" in:
    val saga = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  parallel:
      |    - smithy
      |    - boots
    """.stripMargin).toOption.get
    saga.steps.head match
      case SagaElement.Parallel(steps) =>
        steps.map(_.id) shouldBe List("smithy", "boots")
      case other => fail(s"Expected Parallel, got $other")

  it should "parse mixed sequential and parallel steps in order" in:
    val saga = dsl("""
      |saga: goblin-campaign
      |jar: /libs/goblin.jar
      |steps:
      |  mandatory: measurements
      |  parallel:
      |    - smithy
      |    - boots
      |  optional: portrait
      |  bestEffort: notification
    """.stripMargin).toOption.get
    saga.steps.size shouldBe 4
    saga.steps(0) shouldBe a[SagaElement.Single]
    saga.steps(1) shouldBe a[SagaElement.Parallel]
    saga.steps(2) shouldBe a[SagaElement.Single]
    saga.steps(3) shouldBe a[SagaElement.Single]

  it should "ignore comment lines" in:
    val saga = dsl("""
      |# this is a saga definition
      |saga: goblin-campaign
      |# jar location
      |jar: /libs/goblin.jar
      |steps:
      |  # first step
      |  mandatory: measurements
    """.stripMargin).toOption.get
    saga.id shouldBe "goblin-campaign"
    saga.steps.size shouldBe 1

  it should "ignore blank lines" in:
    val saga = dsl("""
      |saga: goblin-campaign
      |
      |jar: /libs/goblin.jar
      |
      |steps:
      |
      |  mandatory: measurements
      |
    """.stripMargin).toOption.get
    saga.steps.size shouldBe 1

  // ── error cases ───────────────────────────────────────────────────────────

  it should "return Left if saga header is missing" in:
    val result = dsl("""
      |jar: /libs/goblin.jar
      |steps:
      |  mandatory: measurements
    """.stripMargin)
    result shouldBe a[Left[?, ?]]
    result.swap.getOrElse("") should include("saga")

  it should "return Left if jar header is missing" in:
    val result = dsl("""
      |saga: goblin-campaign
      |steps:
      |  mandatory: measurements
    """.stripMargin)
    result shouldBe a[Left[?, ?]]
    result.swap.getOrElse("") should include("jar")

  it should "return Left if steps section is missing" in:
    val result = dsl("""
      |saga: goblin-campaign
      |jar: /libs/goblin.jar
    """.stripMargin)
    result shouldBe a[Left[?, ?]]
    result.swap.getOrElse("") should include("steps")

  it should "return Left if steps section is empty" in:
    val result = dsl("""
      |saga: goblin-campaign
      |jar: /libs/goblin.jar
      |steps:
    """.stripMargin)
    result shouldBe a[Left[?, ?]]

  it should "return Left if parallel block is empty" in:
    val result = dsl("""
      |saga: s
      |jar: /j.jar
      |steps:
      |  parallel:
      |  mandatory: measurements
    """.stripMargin)
    result shouldBe a[Left[?, ?]]
    result.swap.getOrElse("") should include("parallel")
