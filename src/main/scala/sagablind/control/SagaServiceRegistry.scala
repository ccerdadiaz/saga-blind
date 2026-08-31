package sagablind.control

import sagablind.core.SagaDefinition
import sagablind.dsl.SagaDslParser

import java.nio.file.{Files, Path, Paths}

// ── SagaServiceRegistry ─────────────────────────────────────────────────────
// Local service registry — maps saga names to their definitions.
// Populated by the FileWatcher when .saga files appear or disappear.
// The HTTP layer resolves names against this registry — clients never
// need to know where definitions live on disk.
//
// Conceptually equivalent to a local UDDI / OSR:
//   publish(name) → definition available
//   withdraw(name) → definition no longer available
//   resolve(name)  → definition or error

class SagaServiceRegistry:

  private val registry: scala.collection.mutable.Map[String, SagaDefinition] =
    scala.collection.mutable.LinkedHashMap.empty

  /** Publish a saga definition from a .saga file.
   *  Returns Left if the file cannot be read or parsed. */
  def publish(sagaPath: Path): Either[String, SagaDefinition] =
    for
      content    <- readFile(sagaPath)
      definition <- SagaDslParser.parse(content)
      _          =  registry(definition.id) = definition
    yield definition

  /** Withdraw a saga definition by name.
   *  Called when a .saga file is removed from the watched folder. */
  def withdraw(sagaName: String): Unit =
    registry.remove(sagaName)

  /** Withdraw by path — resolves name from the file then removes it. */
  def withdrawByPath(sagaPath: Path): Unit =
    readFile(sagaPath)
      .flatMap(SagaDslParser.parse)
      .foreach(d => registry.remove(d.id))

  /** Resolve a saga name to its definition.
   *  Returns Left if the name is not registered. */
  def resolve(name: String): Either[String, SagaDefinition] =
    registry.get(name).toRight(s"No saga registered under name '$name'")

  /** All currently registered saga names. */
  def available: List[String] = registry.keys.toList.sorted

  /** Number of registered definitions. */
  def size: Int = registry.size

  private def readFile(path: Path): Either[String, String] =
    if !Files.exists(path) then Left(s"File not found: $path")
    else
      try Right(Files.readString(path))
      catch case e: Exception => Left(s"Cannot read file '$path': ${e.getMessage}")
