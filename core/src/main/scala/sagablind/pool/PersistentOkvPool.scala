package sagablind.pool

import sagablind.core.SagaId
import sagablind.store.WalStore

// ── PersistentOkvPool ───────────────────────────────────────────────────────
// OkvPool backed by WalStore. Memory is a read cache over persisted data.
//
// WAL-first: store before memory — if the process dies between the two,
// restore() reconstructs memory from WAL. Never the other way around.
//
// Note: memory could be backed by DB, but it adds latency without improving
// durability guarantees — WAL-first already ensures full recoverability.

class PersistentOkvPool(val sagaId: SagaId, store: WalStore):
  val memory: OkvPool = OkvPool(sagaId)

  /** Deposit a key. WAL first, memory second.
   *  Returns Left if the key already has an owner. */
  def deposit(stepId: String, key: String, value: ujson.Value): Either[String, Unit] =
    for
      _ <- store.depositPoolEntry(sagaId, stepId, key, value.toString)
      _ <- memory.deposit(stepId, key, value)
    yield ()

  /** Deposit all keys produced by a step as a single WAL transaction. */
  def depositDelta(stepId: String, entries: Map[String, ujson.Value]): Either[String, Unit] =
    val delta = entries.map((k, v) => k -> OkvEntry(owner = stepId, value = v))
    for
      _ <- store.depositDelta(sagaId, delta)
      _ <- entries.foldLeft(Right(()): Either[String, Unit]):
             (acc, kv) => acc.flatMap(_ => memory.deposit(stepId, kv._1, kv._2))
    yield ()

  /** Read a key — always from memory (WAL is write-only during execution). */
  def get(key: String): Option[ujson.Value] =
    memory.get(key)

  /** Inject the initial parameters supplied at saga START.
   *  Owner is "__init__" — the saga definition, not any step. */
  def init(params: Map[String, ujson.Value]): Either[String, Unit] =
    depositDelta("__init__", params)

  /** Reconstruct memory from WAL — called by ZombieHunter on resume. */
  def restore(): Either[String, Unit] =
    val rows = store.poolFor(sagaId)
    rows.foldLeft(Right(()): Either[String, Unit]):
      (acc, entry) =>
        val (key, (owner, rawValue)) = entry
        acc.flatMap: _ =>
          ujson.read(rawValue) match
            case v => memory.deposit(owner, key, v)

  /** Full snapshot — for diagnostics. */
  def snapshot: Map[String, OkvEntry] = memory.snapshot

  /** All entries deposited by a given step. */
  def deltaFor(stepId: String): Map[String, OkvEntry] = memory.deltaFor(stepId)
