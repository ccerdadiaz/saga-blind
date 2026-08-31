package sagablind.dsl

import sagablind.core.*

// ── SagaDslParser ───────────────────────────────────────────────────────────
// Hand-written parser for .saga DSL files.
// Produces a SagaDefinition — the engine never sees the raw DSL again.
//
// DSL format:
//   saga: <id>
//   jar:  <path>
//
//   steps:
//     mandatory:  <stepId>
//     optional:   <stepId>
//     bestEffort: <stepId>
//     parallel:
//       - <stepId>
//       - <stepId>
//
// Rules:
//   - Lines starting with # are comments
//   - Blank lines are ignored
//   - Indentation is significant (2 spaces per level)
//   - Step ids map to class names via the jar's SagaStepProvider.descriptor
//   - compensationExtractors are declared in the jar, not in the DSL

object SagaDslParser:

  def parse(content: String): Either[String, SagaDefinition] =
    val lines = content.linesIterator
      .map(_.stripTrailing())
      .filterNot(l => l.isBlank || l.trim.startsWith("#"))
      .toList

    for
      sagaId  <- extractHeader("saga", lines)
      jarPath <- extractHeader("jar",  lines)
      steps   <- extractSteps(lines)
    yield SagaDefinition(id = sagaId, jarPath = jarPath, steps = steps)

  // ── Header extraction ──────────────────────────────────────────────────

  private def extractHeader(key: String, lines: List[String]): Either[String, String] =
    lines
      .find(_.trim.startsWith(s"$key:"))
      .map(_.trim.stripPrefix(s"$key:").trim)
      .filter(_.nonEmpty)
      .toRight(s"Missing or empty '$key:' header")

  // ── Steps extraction ───────────────────────────────────────────────────

  private def extractSteps(lines: List[String]): Either[String, List[SagaElement]] =
    val stepsIdx = lines.indexWhere(_.trim == "steps:")
    if stepsIdx < 0 then
      return Left("Missing 'steps:' section")

    val stepLines = lines.drop(stepsIdx + 1)
      .takeWhile(l => l.startsWith("  "))  // indented under steps:

    parseStepLines(stepLines)

  private def parseStepLines(lines: List[String]): Either[String, List[SagaElement]] =
    val elements = scala.collection.mutable.ListBuffer.empty[SagaElement]
    var i = 0

    while i < lines.size do
      val line   = lines(i)
      val indent = line.takeWhile(_ == ' ').length
      val trimmed = line.trim

      if indent == 2 then  // top-level step keyword
        if trimmed.startsWith("mandatory:") then
          val id = trimmed.stripPrefix("mandatory:").trim
          if id.isEmpty then return Left(s"Empty step id after 'mandatory:' at line ${i+1}")
          elements += SagaElement.Single(stubDescriptor(id, StepKind.Mandatory))
          i += 1

        else if trimmed.startsWith("optional:") then
          val id = trimmed.stripPrefix("optional:").trim
          if id.isEmpty then return Left(s"Empty step id after 'optional:' at line ${i+1}")
          elements += SagaElement.Single(stubDescriptor(id, StepKind.Optional))
          i += 1

        else if trimmed.startsWith("bestEffort:") then
          val id = trimmed.stripPrefix("bestEffort:").trim
          if id.isEmpty then return Left(s"Empty step id after 'bestEffort:' at line ${i+1}")
          elements += SagaElement.Single(stubDescriptor(id, StepKind.BestEffort))
          i += 1

        else if trimmed == "parallel:" then
          i += 1
          val parallelSteps = scala.collection.mutable.ListBuffer.empty[StepDescriptor]
          while i < lines.size && lines(i).takeWhile(_ == ' ').length == 4 do
            val item = lines(i).trim.stripPrefix("- ").trim
            if item.isEmpty then return Left(s"Empty step id in parallel block at line ${i+1}")
            parallelSteps += stubDescriptor(item, StepKind.Mandatory)
            i += 1
          if parallelSteps.isEmpty then return Left("Empty parallel block")
          elements += SagaElement.Parallel(parallelSteps.toList)

        else
          return Left(s"Unknown step keyword: '$trimmed'")
      else
        i += 1  // skip unexpected indentation silently

    if elements.isEmpty then Left("No steps defined")
    else Right(elements.toList)

  // ── Stub descriptor ────────────────────────────────────────────────────
  // The DSL knows the step id. The class name and compensationExtractors
  // are resolved from the jar at load time — not from the DSL.
  // className is set to stepId as a convention placeholder.

  private def stubDescriptor(id: String, kind: StepKind): StepDescriptor =
    StepDescriptor(
      id                     = id,
      kind                   = kind,
      className              = id,   // resolved against jar at load time
      compensationExtractors = Nil,  // declared in SagaStepProvider.descriptor
    )
