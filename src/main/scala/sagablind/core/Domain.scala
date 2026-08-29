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

/** Saga lifecycle states */
enum SagaStatus:
  case Running, Done, Failed, Stopped

/** A single compensation extractor — JSONPath over the pool */
case class CompensationExtractor(
  key:     String,   // name in the WAL
  path:    String,   // "$.weapon.id"
  argType: ArgType,
)

enum ArgType:
  case Str, Int, UUID, Bool

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
