package sagablind.fixture

import sagablind.core.*
import sagablind.loader.SagaStepProvider

// ── FixtureSteps ─────────────────────────────────────────────────────────────
// Minimal SagaStepProvider implementations for JarLoader tests.

class SmithyStep extends SagaStepProvider:
  def stepId: String = "smithy"
  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    Right(Map("weaponId" -> ujson.Str("W-042")))
  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    Right(())

class BootsStep extends SagaStepProvider:
  def stepId: String = "boots"
  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    Right(Map("bootId" -> ujson.Str("B-007")))
  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    Right(())

class BrokenStep extends SagaStepProvider:
  def stepId: String = "broken"
  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    Left(RuntimeException("broken step always fails"))
  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    Right(())
