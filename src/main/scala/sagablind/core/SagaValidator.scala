package sagablind.core

// ── SagaValidator ────────────────────────────────────────────────────────────
// Validates a SagaDefinition semantically before execution.
//
// Rule: a step can only reference owners that have already executed.
//   - __init__ is always available
//   - a step can reference any owner that appears before it in execution order
//   - parallel steps can only reference owners before their parallel block
//   - no step can reference an owner that appears later in the definition
//
// This is detected at load time — before a single step executes.

object SagaValidator:

  def validate(definition: SagaDefinition): Either[String, Unit] =
    val errors = scala.collection.mutable.ListBuffer.empty[String]
    var availableOwners = Set("__init__")

    definition.steps.foreach:
      case SagaElement.Single(descriptor) =>
        validateMappings(descriptor, availableOwners, errors)
        availableOwners += descriptor.id

      case SagaElement.Parallel(steps) =>
        // parallel steps can only see owners before the block — not siblings
        steps.foreach(d => validateMappings(d, availableOwners, errors))
        // after the JOIN all parallel owners become available
        availableOwners ++= steps.map(_.id)

    if errors.isEmpty then Right(())
    else Left(errors.mkString("\n"))

  private def validateMappings(
    descriptor:      StepDescriptor,
    availableOwners: Set[String],
    errors:          scala.collection.mutable.ListBuffer[String],
  ): Unit =
    val allMappings = descriptor.inputMappings ++ descriptor.compensateMappings
    allMappings.foreach: mapping =>
      OkvRef.parse(mapping.from) match
        case Left(err) =>
          errors += s"Step '${descriptor.id}': invalid 'from' expression — $err"
        case Right(ref) =>
          if !availableOwners.contains(ref.owner) then
            errors += s"Step '${descriptor.id}': param '${mapping.param}' references " +
                      s"owner '${ref.owner}' which has not executed yet — " +
                      s"available owners: ${availableOwners.mkString(", ")}"
