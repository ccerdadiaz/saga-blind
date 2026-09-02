package sagablind.store

import sagablind.core.*
import sagablind.pool.OkvEntry

// ── WalStore ─────────────────────────────────────────────────────────────────
// Trait — the WAL contract for saga-blind.
// Implementations: SqliteWalStore (store-sqlite module)
//
// One store per engine (pod). All saga instances share the same store.

trait WalStore:
  def init(): Unit
  def insertSaga(sagaId: SagaId, definition: String, status: SagaStatus): Unit
  def updateSagaStatus(sagaId: SagaId, status: SagaStatus): Unit
  def findSaga(sagaId: SagaId): Option[SagaRow]
  def allSagas(): List[SagaRow]
  def insertStep(sagaId: SagaId, stepId: String, kind: StepKind, status: StepStatus): Unit
  def updateStepStatus(sagaId: SagaId, stepId: String, status: StepStatus): Unit
  def stepsFor(sagaId: SagaId): List[StepRow]
  def depositPoolEntry(sagaId: SagaId, owner: String, key: String, value: String): Either[String, Unit]
  def depositDelta(sagaId: SagaId, delta: Map[String, OkvEntry]): Either[String, Unit]
  def poolFor(sagaId: SagaId): Map[String, (String, String)]
  def insertStepAt(sagaId: SagaId, stepId: String, kind: StepKind, status: StepStatus, at: String): Unit
  def close(): Unit
