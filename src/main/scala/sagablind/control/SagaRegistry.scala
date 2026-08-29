package sagablind.control

import sagablind.core.{SagaId, SagaDefinition, SagaStatus}

// ── SagaRegistry ────────────────────────────────────────────────────────────
// Tracks all saga instances known to the runtime.
// saga-blind list / stop / start / drop operate through this registry.

case class SagaRecord(
  id:         SagaId,
  definition: SagaDefinition,
  status:     SagaStatus,
)

class SagaRegistry:
  private val records: scala.collection.mutable.Map[SagaId, SagaRecord] =
    scala.collection.mutable.LinkedHashMap.empty

  def register(record: SagaRecord): Unit      = records(record.id) = record
  def get(id: SagaId): Option[SagaRecord]     = records.get(id)
  def all: List[SagaRecord]                   = records.values.toList
  def update(id: SagaId, status: SagaStatus): Unit =
    records.get(id).foreach(r => records(id) = r.copy(status = status))
