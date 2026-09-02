package sagablind.control

import sagablind.core.{SagaDefinition, DefinitionStatus}
import sagablind.dsl.SagaDslParser

import java.nio.file.{Files, Path}

// ── SagaControl ──────────────────────────────────────────────────────────────
// Keeps track and manages saga definitions and their lifecycle.
// Populated by the FileWatcher. Queried by the HTTP layer.
//
// Definition lifecycle:
//   Playing → Stopped  (stop)
//   Stopped → Removed  (remove — only when no instances remain)

case class Saga(
  definition: SagaDefinition,
  status:     DefinitionStatus,
)

class SagaControl:

  private val sagas: scala.collection.mutable.Map[String, Saga] =
    scala.collection.mutable.LinkedHashMap.empty

  // ── Publish / Withdraw ────────────────────────────────────────────────────

  def publish(sagaPath: Path): Either[String, SagaDefinition] =
    for
      content    <- readFile(sagaPath)
      definition <- SagaDslParser.parse(content)
      _           = sagas(definition.id) = Saga(definition, DefinitionStatus.Playing)
    yield definition

  def withdraw(sagaName: String): Unit =
    sagas.remove(sagaName)

  // ── Get ───────────────────────────────────────────────────────────────────

  def get(name: String): Either[String, Saga] =
    sagas.get(name).toRight(s"No saga registered under name '$name'")

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  def stop(name: String): Either[String, Unit] =
    sagas.get(name) match
      case None =>
        Left(s"No saga registered under name '$name'")
      case Some(e) if e.status == DefinitionStatus.Stopped =>
        Left(s"Saga '$name' is already stopped")
      case Some(e) =>
        sagas(name) = e.copy(status = DefinitionStatus.Stopped)
        Right(())

  /** Remove — only allowed when Stopped and no instances remain.
   *  instanceCount is provided by SagaRuntime which tracks running instances. */
  def remove(name: String, instanceCount: Int): Either[String, Unit] =
    sagas.get(name) match
      case None =>
        Left(s"No saga registered under name '$name'")
      case Some(e) if e.status != DefinitionStatus.Stopped =>
        Left(s"Saga '$name' must be Stopped before removing (current: ${e.status})")
      case Some(_) if instanceCount > 0 =>
        Left(s"Saga '$name' still has $instanceCount instance(s) in flight — wait for them to complete")
      case Some(_) =>
        sagas.remove(name)
        Right(())

  // ── Query ─────────────────────────────────────────────────────────────────

  def statusOf(name: String): Option[DefinitionStatus] =
    sagas.get(name).map(_.status)

  def available: List[String] =
    sagas.filter(_._2.status == DefinitionStatus.Playing).keys.toList.sorted

  def all: List[Saga] = sagas.values.toList

  def size: Int = sagas.size

  /** Stop all active definitions — called during engine shutdown. */
  def stopAll(): Unit =
    sagas.keys.toList.foreach: name =>
      sagas.get(name).foreach: entry =>
        if entry.status == DefinitionStatus.Playing then
          sagas(name) = entry.copy(status = DefinitionStatus.Stopped)

  private def readFile(path: Path): Either[String, String] =
    if !Files.exists(path) then Left(s"File not found: $path")
    else
      try Right(Files.readString(path))
      catch case e: Exception => Left(s"Cannot read '$path': ${e.getMessage}")
