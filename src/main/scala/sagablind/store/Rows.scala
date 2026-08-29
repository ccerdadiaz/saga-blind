package sagablind.store

import sagablind.core.{SagaId, SagaStatus, StepKind, StepStatus}

// ── Rows ────────────────────────────────────────────────────────────────────
// Plain data containers for DB read results.

case class SagaRow(
  sagaId:     SagaId,
  definition: String,
  status:     SagaStatus,
  startedAt:  String,
  updatedAt:  String,
)

case class StepRow(
  stepId:    String,
  kind:      StepKind,
  status:    StepStatus,
  startedAt: String,
  updatedAt: String,
)
