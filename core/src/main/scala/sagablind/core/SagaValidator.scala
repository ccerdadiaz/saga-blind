package sagablind.core

// ── SagaValidator ────────────────────────────────────────────────────────────
// Validates a SagaDefinition semantically before execution.
//
// Rule 1 — unique step ids:
//   Each step id must be unique across the entire definition, including within
//   parallel blocks. Two steps with the same id are indistinguishable to the
//   engine — the OKV uses the id as owner, so a duplicate id would overwrite
//   the first step's outputs with the second's. ZombieHunter recovery would
//   be unable to reconstruct the correct state.
//
//   If N calls to the same service are needed within a saga, the designer must
//   either: (a) handle both calls in a single step, or (b) create N distinct
//   step ids in the jar that internally call the service as needed.
//
// Rule 2 — inputMappings forward references:
//   A step can only reference owners that have already executed — owners that
//   appear before it in the definition. Steps in a parallel block cannot
//   reference their siblings.
//
// Rule 3 — compensateMappings:
//   A step can reference any owner that has already executed OR its own id —
//   because when compensation runs, the step itself has already deposited its
//   outputs into the pool.
//
// __init__ is always available for both.
//
// State travels as an immutable tuple (availableOwners, errors) through foldLeft —
// no mutable vars or ListBuffers.

object SagaValidator:

  def validate(definition: SagaDefinition): Either[String, Unit] =

    // Rule 1 — unique ids across the entire definition
    val allIds = definition.steps.flatMap:
      case SagaElement.Single(d)    => List(d.id)
      case SagaElement.Parallel(ds) => ds.map(_.id)

    val duplicateErrors = allIds
      .groupBy(identity)
      .filter(_._2.size > 1)
      .keys.toList.sorted
      .map(id => s"Duplicate step id '$id' — each step id must be unique across the entire saga definition")

    // Rule 2 & 3 — owner references via foldLeft
    // State: (availableOwners: Set[String], errors: List[String])
    val (_, ownerErrors) = definition.steps.foldLeft((Set("__init__"), List.empty[String])):
      case ((owners, errs), SagaElement.Single(d)) =>
        val newErrs = errs
          ++ checkInputMappings(d, owners)
          ++ checkCompensateMappings(d, owners + d.id)
        (owners + d.id, newErrs)

      case ((owners, errs), SagaElement.Parallel(steps)) =>
        val newErrs = errs ++ steps.flatMap: d =>
          checkInputMappings(d, owners) ++ checkCompensateMappings(d, owners + d.id)
        (owners ++ steps.map(_.id), newErrs)

    val allErrors = duplicateErrors ++ ownerErrors
    if allErrors.isEmpty then Right(())
    else Left(allErrors.mkString("\n"))

  private def checkInputMappings(
    descriptor:      StepDescriptor,
    availableOwners: Set[String],
  ): List[String] =
    descriptor.inputMappings.flatMap: mapping =>
      OkvRef.parse(mapping.from) match
        case Left(err) =>
          List(s"Step '${descriptor.id}': invalid 'from' in inputs — $err")
        case Right(ref) if !availableOwners.contains(ref.owner) =>
          List(s"Step '${descriptor.id}': input param '${mapping.param}' references " +
               s"owner '${ref.owner}' which has not executed yet — " +
               s"available: ${availableOwners.mkString(", ")}")
        case _ => Nil

  private def checkCompensateMappings(
    descriptor:              StepDescriptor,
    availableOwnersWithSelf: Set[String],
  ): List[String] =
    descriptor.compensateMappings.flatMap: mapping =>
      OkvRef.parse(mapping.from) match
        case Left(err) =>
          List(s"Step '${descriptor.id}': invalid 'from' in compensate — $err")
        case Right(ref) if !availableOwnersWithSelf.contains(ref.owner) =>
          List(s"Step '${descriptor.id}': compensate param '${mapping.param}' references " +
               s"owner '${ref.owner}' which will not be available at compensation time — " +
               s"available: ${availableOwnersWithSelf.mkString(", ")}")
        case _ => Nil
