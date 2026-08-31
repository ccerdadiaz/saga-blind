package sagablind.fixture

import sagablind.core.*
import sagablind.loader.SagaStepProvider
import sagablind.pool.OkvPool

// ── FixtureSteps ─────────────────────────────────────────────────────────────
// Minimal SagaStepProvider implementations for JarLoader tests.
// These are compiled into the test classpath — not into a real jar.
// JarLoaderSpec uses them via the test classloader to avoid jar compilation.

class SmithyStep extends SagaStepProvider:
  def descriptor: StepDescriptor = StepDescriptor(
    id                     = "smithy",
    kind                   = StepKind.Mandatory,
    className              = "sagablind.fixture.SmithyStep",
    compensationExtractors = List(
      CompensationExtractor("weaponId", "$.weaponId", ArgType.UUID)
    ),
  )
  def execute(pool: OkvPool): Either[Throwable, Unit] =
    pool.deposit("smithy", "weaponId", ujson.Str("W-042")).left.map(RuntimeException(_))
  def compensate(args: Map[String, String]): Either[Throwable, Unit] =
    Right(()) // no-op in tests

class BootsStep extends SagaStepProvider:
  def descriptor: StepDescriptor = StepDescriptor(
    id                     = "boots",
    kind                   = StepKind.Mandatory,
    className              = "sagablind.fixture.BootsStep",
    compensationExtractors = List(
      CompensationExtractor("bootId", "$.bootId", ArgType.Str)
    ),
  )
  def execute(pool: OkvPool): Either[Throwable, Unit] =
    pool.deposit("boots", "bootId", ujson.Str("B-007")).left.map(RuntimeException(_))
  def compensate(args: Map[String, String]): Either[Throwable, Unit] =
    Right(())

class BrokenStep extends SagaStepProvider:
  def descriptor: StepDescriptor = StepDescriptor(
    id                     = "broken",
    kind                   = StepKind.Mandatory,
    className              = "sagablind.fixture.BrokenStep",
    compensationExtractors = Nil,
  )
  def execute(pool: OkvPool): Either[Throwable, Unit] =
    Left(RuntimeException("broken step always fails"))
  def compensate(args: Map[String, String]): Either[Throwable, Unit] =
    Right(())
