package sagablind.loader

import sagablind.core.StepDescriptor
import sagablind.pool.OkvPool

// ── SagaStepProvider ────────────────────────────────────────────────────────
// The contract every class in the jar must implement.
// The engine loads these via URLClassLoader — it never sees the concrete type.

trait SagaStepProvider:
  def descriptor: StepDescriptor

  /** Execute the step. Read inputs from pool, deposit outputs to pool. */
  def execute(pool: OkvPool): Either[Throwable, Unit]

  /** Compensate using args extracted from pool by the engine after execute. */
  def compensate(args: Map[String, String]): Either[Throwable, Unit]
