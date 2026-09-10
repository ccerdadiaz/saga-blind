package sagablind.store

import sagablind.core.*
import sagablind.pool.OkvEntry

import java.time.Instant

// ── InMemoryWalStore ──────────────────────────────────────────────────────────
// In-memory implementation of WalStore for use in core tests.
// No persistence — resets on every test.

class InMemoryWalStore extends WalStore:

  private val sagas = scala.collection.mutable.Map.empty[SagaId, SagaRow]
  private val steps = scala.collection.mutable.Map.empty[SagaId, scala.collection.mutable.ListBuffer[StepRow]]
  private val pool  = scala.collection.mutable.Map.empty[(SagaId, String, String), String]

  def init(): Unit = ()

  // ── Saga ──────────────────────────────────────────────────────────────────

  def insertSaga(sagaId: SagaId, definition: String, status: SagaStatus): Unit =
    val now = Instant.now().toString
    sagas(sagaId) = SagaRow(sagaId, definition, status, now, now)

  def updateSagaStatus(sagaId: SagaId, status: SagaStatus): Unit =
    sagas.get(sagaId).foreach: row =>
      sagas(sagaId) = row.copy(status = status, updatedAt = Instant.now().toString)

  def findSaga(sagaId: SagaId): Option[SagaRow] = sagas.get(sagaId)

  def allSagas(): List[SagaRow] = sagas.values.toList

  // ── Step ──────────────────────────────────────────────────────────────────

  def insertStep(sagaId: SagaId, stepId: String, kind: StepKind, status: StepStatus): Unit =
    val now = Instant.now().toString
    val row = StepRow(stepId, kind, status, now, now)
    steps.getOrElseUpdate(sagaId, scala.collection.mutable.ListBuffer.empty) += row

  def insertStepAt(sagaId: SagaId, stepId: String, kind: StepKind, status: StepStatus, at: String): Unit =
    val row = StepRow(stepId, kind, status, at, at)
    steps.getOrElseUpdate(sagaId, scala.collection.mutable.ListBuffer.empty) += row

  def updateStepStatus(sagaId: SagaId, stepId: String, status: StepStatus): Unit =
    steps.get(sagaId).foreach: buf =>
      val idx = buf.indexWhere(_.stepId == stepId)
      if idx >= 0 then
        buf(idx) = buf(idx).copy(status = status, updatedAt = Instant.now().toString)

  def stepsFor(sagaId: SagaId): List[StepRow] =
    steps.getOrElse(sagaId, scala.collection.mutable.ListBuffer.empty).toList

  // ── Pool ──────────────────────────────────────────────────────────────────

  def depositPoolEntry(sagaId: SagaId, owner: String, key: String, value: String): Either[String, Unit] =
    val k = (sagaId, owner, key)
    if pool.contains(k) then Left(s"Key '$key' already owned by '$owner'")
    else
      pool(k) = value
      Right(())

  def depositDelta(sagaId: SagaId, delta: Map[String, OkvEntry]): Either[String, Unit] =
    // check all keys first — atomicity: write nothing if any conflict
    val existingKeys = pool.keySet.filter(_._1 == sagaId).map(_._3)
    val conflicts    = delta.keys.filter(existingKeys.contains)
    if conflicts.nonEmpty then
      Left(s"Delta rejected — keys already owned: ${conflicts.mkString(", ")}")
    else
      delta.foreach: (key, entry) =>
        pool((sagaId, entry.owner, key)) = entry.value.toString
      Right(())

  def poolFor(sagaId: SagaId): Map[String, (String, String)] =
    pool
      .filter(_._1._1 == sagaId)
      .collect { case ((_, owner, key), value) =>
        key -> (owner, value)
      }
      .toMap

  def close(): Unit = ()
