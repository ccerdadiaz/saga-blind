package sagablind.core

// ── SagaValidator ────────────────────────────────────────────────────────────
// Validates a SagaDefinition semantically before execution.
//
// Rule for inputMappings: a step can only reference owners that have already
// executed — owners that appear before it in the definition.
//
// Rule for compensateMappings: a step can reference any owner that has already
// executed OR its own id — because when compensation runs, the step itself
// has already deposited its outputs into the pool.
//
// __init__ is always available for both.

object SagaValidator:

  def validate(definition: SagaDefinition): Either[String, Unit] =
    val errors = scala.collection.mutable.ListBuffer.empty[String]
    var availableOwners = Set("__init__")

    definition.steps.foreach:
      case SagaElement.Single(descriptor) =>
        validateInputMappings(descriptor, availableOwners, errors)
        validateCompensateMappings(descriptor, availableOwners + descriptor.id, errors)
        availableOwners += descriptor.id

      case SagaElement.Parallel(steps) =>
        // parallel steps can only see owners before the block — not siblings
        steps.foreach: d =>
          validateInputMappings(d, availableOwners, errors)
          validateCompensateMappings(d, availableOwners + d.id, errors)
        // after the JOIN all parallel owners become available
        availableOwners ++= steps.map(_.id)

    if errors.isEmpty then Right(())
    else Left(errors.mkString("\n"))

  private def validateInputMappings(
    descriptor:      StepDescriptor,
    availableOwners: Set[String],
    errors:          scala.collection.mutable.ListBuffer[String],
  ): Unit =
    descriptor.inputMappings.foreach: mapping =>
      OkvRef.parse(mapping.from) match
        case Left(err) =>
          errors += s"Step '${descriptor.id}': invalid 'from' in inputs — $err"
        case Right(ref) =>
          if !availableOwners.contains(ref.owner) then
            errors += s"Step '${descriptor.id}': input param '${mapping.param}' references " +
                      s"owner '${ref.owner}' which has not executed yet — " +
                      s"available: ${availableOwners.mkString(", ")}"

  private def validateCompensateMappings(
    descriptor:               StepDescriptor,
    availableOwnersWithSelf:  Set[String],   // includes the step's own id
    errors:                   scala.collection.mutable.ListBuffer[String],
  ): Unit =
    descriptor.compensateMappings.foreach: mapping =>
      OkvRef.parse(mapping.from) match
        case Left(err) =>
          errors += s"Step '${descriptor.id}': invalid 'from' in compensate — $err"
        case Right(ref) =>
          if !availableOwnersWithSelf.contains(ref.owner) then
            errors += s"Step '${descriptor.id}': compensate param '${mapping.param}' references " +
                      s"owner '${ref.owner}' which will not be available at compensation time — " +
                      s"available: ${availableOwnersWithSelf.mkString(", ")}"
