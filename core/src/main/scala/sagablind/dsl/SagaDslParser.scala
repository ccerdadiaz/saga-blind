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

    def go(remaining: List[String], acc: List[SagaElement]): Either[String, List[SagaElement]] =
      remaining match
       case Nil => 
         if acc.isEmpty then Left("No steps defined")
         else Right(acc.reverse)
      
       case line :: rest if line.trim.startsWith("- parallel:") =>
         val parallelLines = rest.takeWhile(_.takeWhile(_ == ' ').length >= 4)
         parseParallelSteps(parallelLines) match
           case Left(err)    => Left(err)
           case Right(Nil)   => Left("Empty parallel block")
           case Right(steps) => go(rest.drop(parallelLines.size), SagaElement.Parallel(steps) :: acc)

       case line :: _ if line.trim.startsWith("- id:") =>
         val block = collectBlock(remaining, 0)
         parseStep(block) match
           case Left(err)   => Left(err)
           case Right(desc) => go(remaining.drop(block.size), SagaElement.Single(desc) :: acc)

       case _ :: rest => go(rest, acc)

    go(lines, Nil)

  private def parseParallelSteps(lines: List[String]): Either[String, List[StepDescriptor]] =

    def go(remaining: List[String], acc: List[StepDescriptor]): Either[String, List[StepDescriptor]] =
      remaining match
        case Nil => Right(acc.reverse)

        case line :: _ if line.trim.startsWith("- id:") =>
          val block = collectBlock(remaining, 0)
          parseStep(block) match
            case Left(err)   => Left(err)
            case Right(desc) => go(remaining.drop(block.size), desc :: acc)

        case _ :: rest => go(rest, acc)

    go(lines, Nil)

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
      .takeWhile(_.takeWhile(_ == ' ').length > sectionIndent)

    def go(remaining: List[String], acc: List[ParamMapping]): List[ParamMapping] =
      remaining match
        case Nil => acc.reverse

        case line :: rest if line.trim.startsWith("- param:") =>
          val param   = line.trim.stripPrefix("- param:").trim
          val fromOpt = rest.find(_.trim.startsWith("from:"))
                          .map(_.trim.stripPrefix("from:").trim)
          fromOpt match
            case Some(from) if param.nonEmpty && from.nonEmpty =>
              go(rest, ParamMapping(param = param, from = from) :: acc)
            case _ =>
              go(rest, acc)

        case _ :: rest => go(rest, acc)

    go(sectionLines, Nil)

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
