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
//     - id: <stepId>
//       kind: mandatory | optional | bestEffort
//       class: <className>
//       inputs:
//         - param: <paramName>
//           from: <owner>/<key>[.jsonPath]
//       compensate:
//         inputs:
//           - param: <paramName>
//             from: <owner>/<key>[.jsonPath]
//
//     - parallel:
//       - id: <stepId>
//         ...
//       - id: <stepId>
//         ...

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

  // ── Header ────────────────────────────────────────────────────────────────

  private def extractHeader(key: String, lines: List[String]): Either[String, String] =
    lines
      .find(_.trim.startsWith(s"$key:"))
      .map(_.trim.stripPrefix(s"$key:").trim)
      .filter(_.nonEmpty)
      .toRight(s"Missing or empty '$key:' header")

  // ── Steps section ─────────────────────────────────────────────────────────

  private def extractSteps(lines: List[String]): Either[String, List[SagaElement]] =
    val stepsIdx = lines.indexWhere(_.trim == "steps:")
    if stepsIdx < 0 then return Left("Missing 'steps:' section")

    val stepLines = lines.drop(stepsIdx + 1).takeWhile(l => l.startsWith("  "))
    if stepLines.isEmpty then return Left("No steps defined")

    parseElements(stepLines)

  // ── Element parsing ───────────────────────────────────────────────────────

  private def parseElements(lines: List[String]): Either[String, List[SagaElement]] =
    val elements = scala.collection.mutable.ListBuffer.empty[SagaElement]
    var i = 0

    while i < lines.size do
      val line    = lines(i)
      val trimmed = line.trim
      val indent  = line.takeWhile(_ == ' ').length

      if indent == 2 && trimmed.startsWith("- parallel:") then
        // parallel block — collect child steps at indent 4
        i += 1
        val parallelLines = lines.drop(i).takeWhile(l => l.takeWhile(_ == ' ').length >= 4)
        parseParallelSteps(parallelLines) match
          case Left(err)    => return Left(err)
          case Right(steps) =>
            if steps.isEmpty then return Left("Empty parallel block")
            elements += SagaElement.Parallel(steps)
            i += parallelLines.size

      else if indent == 2 && trimmed.startsWith("- id:") then
        // single step block — collect all lines of this step
        val stepLines = lines.drop(i).takeWhile: l =>
          val ind = l.takeWhile(_ == ' ').length
          ind > 2 || (ind == 2 && l.trim.startsWith("- id:") && l == lines(i))
        val blockLines = collectBlock(lines, i)
        parseStep(blockLines) match
          case Left(err)       => return Left(err)
          case Right(descriptor) =>
            elements += SagaElement.Single(descriptor)
            i += blockLines.size
      else
        i += 1

    if elements.isEmpty then Left("No steps defined")
    else Right(elements.toList)

  private def parseParallelSteps(lines: List[String]): Either[String, List[StepDescriptor]] =
    val steps  = scala.collection.mutable.ListBuffer.empty[StepDescriptor]
    var i      = 0
    while i < lines.size do
      val line    = lines(i)
      val trimmed = line.trim
      if trimmed.startsWith("- id:") then
        val block = collectBlock(lines, i)
        parseStep(block) match
          case Left(err) => return Left(err)
          case Right(d)  => steps += d
        i += block.size
      else
        i += 1
    Right(steps.toList)

  /** Collect all lines belonging to a step block starting at index i */
  private def collectBlock(lines: List[String], start: Int): List[String] =
    val startIndent = lines(start).takeWhile(_ == ' ').length
    lines.drop(start).zipWithIndex.takeWhile: (line, idx) =>
      idx == 0 || line.takeWhile(_ == ' ').length > startIndent || line.trim.isEmpty
    .map(_._1)

  // ── Step parsing ──────────────────────────────────────────────────────────

  private def parseStep(lines: List[String]): Either[String, StepDescriptor] =
    val kv = parseKeyValues(lines)

    for
      id        <- kv.get("id").toRight("Step missing 'id'")
      className <- kv.get("class").toRight(s"Step '$id' missing 'class'")
      kind      <- parseKind(kv.getOrElse("kind", "mandatory"), id)
      inputs     = parseMappings(lines, "inputs:")
      compensate = parseMappings(lines, "compensate:")
    yield StepDescriptor(
      id                 = id,
      kind               = kind,
      className          = className,
      inputMappings      = inputs,
      compensateMappings = compensate,
    )

  private def parseKind(s: String, stepId: String): Either[String, StepKind] =
    s.trim.toLowerCase match
      case "mandatory"   => Right(StepKind.Mandatory)
      case "optional"    => Right(StepKind.Optional)
      case "besteffort"  => Right(StepKind.BestEffort)
      case other         => Left(s"Step '$stepId': unknown kind '$other'")

  // ── Mapping parsing ───────────────────────────────────────────────────────

  private def parseMappings(lines: List[String], section: String): List[ParamMapping] =
    val sectionIdx = lines.indexWhere(_.trim == section)
    if sectionIdx < 0 then return Nil

    val sectionIndent = lines(sectionIdx).takeWhile(_ == ' ').length
    val sectionLines  = lines.drop(sectionIdx + 1)
      .takeWhile(l => l.takeWhile(_ == ' ').length > sectionIndent)

    // group by "- param:" entries
    val mappings = scala.collection.mutable.ListBuffer.empty[ParamMapping]
    var i = 0
    while i < sectionLines.size do
      val line    = sectionLines(i)
      val trimmed = line.trim
      if trimmed.startsWith("- param:") then
        val param = trimmed.stripPrefix("- param:").trim
        // look for 'from:' on next line
        val fromLine = sectionLines.drop(i + 1)
          .find(_.trim.startsWith("from:"))
          .map(_.trim.stripPrefix("from:").trim)
          .getOrElse("")
        if param.nonEmpty && fromLine.nonEmpty then
          mappings += ParamMapping(param = param, from = fromLine)
      i += 1

    mappings.toList

  // ── Utility ───────────────────────────────────────────────────────────────

  private def parseKeyValues(lines: List[String]): Map[String, String] =
    lines.flatMap: line =>
      val trimmed = line.trim.stripPrefix("- ")
      val colonIdx = trimmed.indexOf(':')
      if colonIdx > 0 then
        val key   = trimmed.substring(0, colonIdx).trim
        val value = trimmed.substring(colonIdx + 1).trim
        if value.nonEmpty then Some(key -> value) else None
      else None
    .toMap
