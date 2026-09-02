package sagablind.control

import sagablind.core.{SagaDefinition, DefinitionStatus}
import sagablind.dsl.SagaDslParser

import java.nio.file.{Files, Path}

// ── SagaControl  ─────────────────────────────────────────────────────
// Keeps track and manages sagas and it's lifecicles.
// Populated by the FileWatcher. Queried by the HTTP layer.
//
// Definition lifecycle — play/pause/stop/remove:
//
//   Playing → Paused   (pause)
//   Paused  → Playing  (continue)
//   Playing | Paused → Stopped  (stop — compensates in-flight instances)
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

  // ── get  ──────────────────────────────────────────────────────────
  def get(name: String): Either[String, Saga] =
    sagas.get(name).toRight(s"No saga registered under name '$name'")
                                         
  // ── Lifecycle ─────────────────────────────────────────────────────────────

  def pause(name: String): Either[String, Unit] =
    transition(name, DefinitionStatus.Playing, DefinitionStatus.Paused)

  def continue(name: String): Either[String, Unit] =
    transition(name, DefinitionStatus.Paused, DefinitionStatus.Playing)

  def stop(name: String): Either[String, Unit] =
    sagas.get(name) match
      case None => Left(s"No saga registered under name '$name'")
      case Some(e) if e.status == DefinitionStatus.Removed =>
        Left(s"Saga '$name' is already removed")
      case Some(e) =>
        sagas(name) = e.copy(status = DefinitionStatus.Stopped)
        Right(())

  /** Remove — only allowed when Stopped and no instances remain.
   *  instanceCount is provided by SagaRuntime which tracks running instances. */
  def remove(name: String, instanceCount: Int): Either[String, Unit] =
    sagas.get(name) match
      case None => Left(s"No saga registered under name '$name'")
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

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def transition(
    name: String,
    from: DefinitionStatus,
    to:   DefinitionStatus,
  ): Either[String, Unit] =
    sagas.get(name) match
      case None => Left(s"No saga registered under name '$name'")
      case Some(e) if e.status != from =>
        Left(s"Saga '$name' is ${e.status} — cannot transition to $to")
      case Some(e) =>
        sagas(name) = e.copy(status = to)
        Right(())

  private def readFile(path: Path): Either[String, String] =
    if !Files.exists(path) then Left(s"File not found: $path")
    else
      try Right(Files.readString(path))
      catch case e: Exception => Left(s"Cannot read '$path': ${e.getMessage}")

  /** Stop all active definitions — called during engine shutdown.
   *  Playing and Paused definitions transition to Stopped.
   *  In-flight instances will compensate when their current step finishes. */
  def stopAll(): Unit =
    sagas.keys.toList.foreach: name =>
      sagas.get(name).foreach: entry =>
        entry.status match
          case DefinitionStatus.Playing | DefinitionStatus.Paused =>
            sagas(name) = entry.copy(status = DefinitionStatus.Stopped)
          case _ => ()
