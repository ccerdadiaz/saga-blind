package sagablind.core

// ── Domain ──────────────────────────────────────────────────────────────────
// Core types for saga-blind.
// The engine is blind to domain — these types describe structure, not meaning.

opaque type SagaId = String
object SagaId:
  def apply(s: String): SagaId = s
  def generate(): SagaId       = java.util.UUID.randomUUID().toString
  extension (id: SagaId) def value: String = id

/** Step semantics */
enum StepKind:
  case Mandatory, Optional, BestEffort

/** Step lifecycle states */
enum StepStatus:
  case Registered, Done, Failed, Unknown

/** Saga instance lifecycle states */
enum SagaStatus:
  case Running, Done, Failed, Stopped, PausedBetweenSteps, Compensated

/** Definition lifecycle */
enum DefinitionStatus:
  case Playing, Paused, Stopped, Removed

// ── Parameter mapping ────────────────────────────────────────────────────────
// Describes how to extract a value from the OKV pool and bind it to a parameter.
//
// Syntax: owner/key.jsonPath
//   owner — the step that deposited the value, or __init__ for saga params
//   key   — the OKV key
//   path  — optional JSONPath expression to navigate inside the JSON value
//
// Examples:
//   __init__/goblinId               → full value of goblinId from init params
//   measurements/result.head        → field 'head' inside measurements result
//   A/candidateCollection[1]        → second element of A's candidateCollection

case class ParamMapping(
  param: String,   // name of the target parameter
  from:  String,   // "owner/key" or "owner/key.jsonPath" or "owner/key[n]"
)

/** Parsed form of a 'from' expression */
case class OkvRef(
  owner:    String,          // step id or __init__
  key:      String,          // OKV key
  jsonPath: Option[String],  // optional JSONPath within the value
)

object OkvRef:
  /** Parse "owner/key" or "owner/key.path" or "owner/key[n]" */
  def parse(from: String): Either[String, OkvRef] =
    val slashIdx = from.indexOf('/')
    if slashIdx < 0 then
      Left(s"Invalid 'from' expression '$from' — expected owner/key[.path]")
    else
      val owner = from.substring(0, slashIdx).trim
      val rest  = from.substring(slashIdx + 1).trim
      // split key from optional jsonPath at first '.' or '['
      val pathStart = rest.indexWhere(c => c == '.' || c == '[')
      if pathStart < 0 then
        Right(OkvRef(owner, rest, None))
      else
        val key      = rest.substring(0, pathStart)
        val jsonPath = rest.substring(pathStart)
        Right(OkvRef(owner, key, Some(jsonPath)))

// ── Step descriptor ───────────────────────────────────────────────────────────
// Describes a step as declared in the DSL.
// className maps to a class in the jar that implements SagaStepProvider.
// The engine uses inputMappings to build the call args from the OKV.
// The engine uses compensateMappings to build the compensation args from the OKV.

case class StepDescriptor(
  id:                 String,
  kind:               StepKind,
  className:          String,
  inputMappings:      List[ParamMapping],
  compensateMappings: List[ParamMapping],
)

/** The saga definition — parsed from the DSL file */
case class SagaDefinition(
  id:      String,
  jarPath: String,
  steps:   List[SagaElement],
)

// SagaElement — unit of execution in a saga definition.
// A saga is a sequence of elements; each element is either
// a single step or a group of steps that execute concurrently.
// The step+compensation contract lives in StepDescriptor.
enum SagaElement:
  case Single(descriptor: StepDescriptor)
  case Parallel(steps: List[StepDescriptor])
