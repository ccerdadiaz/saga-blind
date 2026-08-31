package sagablind.core

// ── Domain ──────────────────────────────────────────────────────────────────
// Core types for saga-blind.
// The engine is blind to domain — these types describe structure, not meaning.

opaque type SagaId = String
object SagaId:
  def apply(s: String): SagaId = s
  def generate(): SagaId       = java.util.UUID.randomUUID().toString
  extension (id: SagaId) def value: String = id

/** Step semantics — same vocabulary as saga-graph */
enum StepKind:
  case Mandatory, Optional, BestEffort

/** Step lifecycle states */
enum StepStatus:
  case Registered, Done, Failed, Unknown

/** Saga instance lifecycle states */
enum SagaStatus:
  case Running, Done, Failed, Stopped, PausedBetweenSteps, Compensated

/** Definition lifecycle — play/pause/stop/remove model */
enum DefinitionStatus:
  case Playing, Paused, Stopped, Removed

/** A single compensation extractor — JSONPath over the pool */
case class CompensationExtractor(
  key:     String,   // name in the WAL
  path:    String,   // "$.weapon.id"
  argType: ArgType,
)

// Note: JSON numbers are represented as Double (ujson limitation).
// BigDecimal precision is not supported — use Str with explicit
// conversion in the step's adapter layer if needed.
enum ArgType:
  case Str, Int, Float, UUID, Bool, Arr

/** Descriptor declared by the jar — the contract between jar and engine */
case class StepDescriptor(
  id:                     String,
  kind:                   StepKind,
  className:              String,
  compensationExtractors: List[CompensationExtractor],
)

/** The saga definition — parsed from the DSL file */
case class SagaDefinition(
  id:      String,
  jarPath: String,
  steps:   List[SagaElement],
)

enum SagaElement:
  case Single(descriptor: StepDescriptor)
  case Parallel(steps: List[StepDescriptor])
