package sagablind.store

// ── Schema ──────────────────────────────────────────────────────────────────
// SQLite DDL for saga-blind WAL.
// Three tables — saga, saga_step, saga_pool.
// The PRIMARY KEY on saga_pool(saga_id, key) enforces OKV ownership at DB level.

object Schema:
  val createSaga: String =
    """
    CREATE TABLE IF NOT EXISTS saga (
      saga_id    TEXT PRIMARY KEY,
      definition TEXT NOT NULL,
      status     TEXT NOT NULL,
      started_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
    """.trim

  val createSagaStep: String =
    """
    CREATE TABLE IF NOT EXISTS saga_step (
      saga_id    TEXT NOT NULL,
      step_id    TEXT NOT NULL,
      kind       TEXT NOT NULL,
      status     TEXT NOT NULL,
      started_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      PRIMARY KEY (saga_id, step_id),
      FOREIGN KEY (saga_id) REFERENCES saga(saga_id)
    )
    """.trim

  val createSagaPool: String =
    """
    CREATE TABLE IF NOT EXISTS saga_pool (
      saga_id      TEXT NOT NULL,
      owner        TEXT NOT NULL,
      key          TEXT NOT NULL,
      value        TEXT NOT NULL,
      deposited_at TEXT NOT NULL,
      PRIMARY KEY (saga_id, key),
      FOREIGN KEY (saga_id) REFERENCES saga(saga_id)
    )
    """.trim

  val all: List[String] = List(createSaga, createSagaStep, createSagaPool)
