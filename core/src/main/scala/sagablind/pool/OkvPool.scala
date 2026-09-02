package sagablind.pool

import sagablind.core.SagaId

// ── OkvPool ─────────────────────────────────────────────────────────────────
// Owner Key Value blackboard — one per saga instance, isolated by sagaId.
//
// Invariant: a key has exactly one owner.
// No two steps can deposit the same key — the pool enforces this structurally.
// The full pool is persisted in the WAL as deltas after each step.

case class OkvEntry(
  owner: String,       // stepId that deposited this key
  value: ujson.Value,
)

class OkvPool(val sagaId: SagaId):
  private val store: scala.collection.mutable.Map[String, OkvEntry] =
    scala.collection.mutable.LinkedHashMap.empty

  /** Deposit a key. Fails if the key already has an owner. */
  def deposit(stepId: String, key: String, value: ujson.Value): Either[String, Unit] =
    store.get(key) match
      case Some(existing) =>
        Left(s"Key '$key' already owned by '${existing.owner}' — step '$stepId' cannot claim it")
      case None =>
        store(key) = OkvEntry(owner = stepId, value = value)
        Right(())

  /** Read a key. Returns None if not yet deposited. */
  def get(key: String): Option[ujson.Value] =
    store.get(key).map(_.value)

  /** All entries deposited by a given step — the delta for WAL persistence */
  def deltaFor(stepId: String): Map[String, OkvEntry] =
    store.filter((_, e) => e.owner == stepId).toMap

  /** Full snapshot — for reconstruction after failure */
  def snapshot: Map[String, OkvEntry] =
    store.toMap

  /** Read a key by owner and key name — enforces ownership awareness. */
  def getByOwner(owner: String, key: String): Option[ujson.Value] =
    store.get(key).filter(_.owner == owner).map(_.value)
