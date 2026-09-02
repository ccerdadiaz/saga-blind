package sagablind.loader

import sagablind.core.StepDescriptor

// ── SagaStepProvider ────────────────────────────────────────────────────────
// Contract every class in the jar must implement.
// The engine loads these via URLClassLoader — it never sees the concrete type.
//
// The jar is pure business logic — it knows nothing about the OKV pool.
// Parameter binding (OKV → method args) is the engine's responsibility,
// declared in the DSL via 'from: owner/key.jsonPath' expressions.
//
// Both execute and compensate receive args as Map[String, ujson.Value]:
//   - keys are the param names declared in the DSL inputMappings
//   - values are extracted from the OKV by the engine before the call
//
// Note: JSON numbers are represented as Double (ujson limitation).
// BigDecimal precision is not supported — use Str with explicit
// conversion in the step's adapter layer if needed.

trait SagaStepProvider:

  /** Unique step id — must match the id declared in the DSL. */
  def stepId: String

  /** Execute the step with args extracted from the OKV by the engine.
   *  Returns the output to be deposited in the OKV under this step's ownership. */
  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]]

  /** Compensate using args extracted from the OKV by the engine.
   *  The compensation class is always the same as the execute class. */
  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit]
