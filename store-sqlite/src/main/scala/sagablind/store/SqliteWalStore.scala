package sagablind.store

import sagablind.core.*
import sagablind.pool.OkvEntry

import java.sql.{Connection, DriverManager, Timestamp}
import java.time.Instant

// ── WalStore ────────────────────────────────────────────────────────────────
// SQLite-backed WAL for saga-blind.
// One DB per engine (pod). All saga instances share the same DB.
// Thread-safety: each operation uses a fresh statement; connection is shared.

class SqliteWalStore(dbPath: String) extends WalStore:

  Class.forName("org.sqlite.JDBC")
  private val conn: Connection =
    DriverManager.getConnection(s"jdbc:sqlite:$dbPath")
  conn.setAutoCommit(false)

  // ── Init ──────────────────────────────────────────────────────────────────

  def init(): Unit =
    Schema.all.foreach: ddl =>
      val st = conn.createStatement()
      st.execute(ddl)
      st.close()
    conn.commit()

  // ── Saga ──────────────────────────────────────────────────────────────────

  def insertSaga(sagaId: SagaId, definition: String, status: SagaStatus): Unit =
    val now = Instant.now().toString
    val st  = conn.prepareStatement(
      "INSERT INTO saga (saga_id, definition, status, started_at, updated_at) VALUES (?,?,?,?,?)"
    )
    st.setString(1, sagaId.value)
    st.setString(2, definition)
    st.setString(3, status.toString)
    st.setString(4, now)
    st.setString(5, now)
    st.executeUpdate()
    st.close()
    conn.commit()

  def updateSagaStatus(sagaId: SagaId, status: SagaStatus): Unit =
    val now = Instant.now().toString
    val st  = conn.prepareStatement(
      "UPDATE saga SET status = ?, updated_at = ? WHERE saga_id = ?"
    )
    st.setString(1, status.toString)
    st.setString(2, now)
    st.setString(3, sagaId.value)
    st.executeUpdate()
    st.close()
    conn.commit()

  def findSaga(sagaId: SagaId): Option[SagaRow] =
    val st = conn.prepareStatement(
      "SELECT saga_id, definition, status, started_at, updated_at FROM saga WHERE saga_id = ?"
    )
    st.setString(1, sagaId.value)
    val rs = st.executeQuery()
    val result =
      if rs.next() then
        Some(SagaRow(
          sagaId     = SagaId(rs.getString("saga_id")),
          definition = rs.getString("definition"),
          status     = SagaStatus.valueOf(rs.getString("status")),
          startedAt  = rs.getString("started_at"),
          updatedAt  = rs.getString("updated_at"),
        ))
      else None
    rs.close(); st.close()
    result

  def allSagas(): List[SagaRow] =
    val st = conn.prepareStatement(
      "SELECT saga_id, definition, status, started_at, updated_at FROM saga ORDER BY started_at DESC"
    )
    val rs     = st.executeQuery()
    val buffer = scala.collection.mutable.ListBuffer.empty[SagaRow]
    while rs.next() do
      buffer += SagaRow(
        sagaId     = SagaId(rs.getString("saga_id")),
        definition = rs.getString("definition"),
        status     = SagaStatus.valueOf(rs.getString("status")),
        startedAt  = rs.getString("started_at"),
        updatedAt  = rs.getString("updated_at"),
      )
    rs.close(); st.close()
    buffer.toList

  // ── Step ──────────────────────────────────────────────────────────────────

  def insertStep(sagaId: SagaId, stepId: String, kind: StepKind, status: StepStatus): Unit =
    val now = Instant.now().toString
    val st  = conn.prepareStatement(
      "INSERT INTO saga_step (saga_id, step_id, kind, status, started_at, updated_at) VALUES (?,?,?,?,?,?)"
    )
    st.setString(1, sagaId.value)
    st.setString(2, stepId)
    st.setString(3, kind.toString)
    st.setString(4, status.toString)
    st.setString(5, now)
    st.setString(6, now)
    st.executeUpdate()
    st.close()
    conn.commit()

  def updateStepStatus(sagaId: SagaId, stepId: String, status: StepStatus): Unit =
    val now = Instant.now().toString
    val st  = conn.prepareStatement(
      "UPDATE saga_step SET status = ?, updated_at = ? WHERE saga_id = ? AND step_id = ?"
    )
    st.setString(1, status.toString)
    st.setString(2, now)
    st.setString(3, sagaId.value)
    st.setString(4, stepId)
    st.executeUpdate()
    st.close()
    conn.commit()

  def stepsFor(sagaId: SagaId): List[StepRow] =
    val st = conn.prepareStatement(
      "SELECT step_id, kind, status, started_at, updated_at FROM saga_step WHERE saga_id = ? ORDER BY started_at ASC"
    )
    st.setString(1, sagaId.value)
    val rs     = st.executeQuery()
    val buffer = scala.collection.mutable.ListBuffer.empty[StepRow]
    while rs.next() do
      buffer += StepRow(
        stepId    = rs.getString("step_id"),
        kind      = StepKind.valueOf(rs.getString("kind")),
        status    = StepStatus.valueOf(rs.getString("status")),
        startedAt = rs.getString("started_at"),
        updatedAt = rs.getString("updated_at"),
      )
    rs.close(); st.close()
    buffer.toList

  // ── Pool ──────────────────────────────────────────────────────────────────

  /** Deposit one OKV entry. Returns Left if the key already exists (ownership violated). */
  def depositPoolEntry(sagaId: SagaId, owner: String, key: String, value: String): Either[String, Unit] =
    val now = Instant.now().toString
    val st  = conn.prepareStatement(
      "INSERT INTO saga_pool (saga_id, owner, key, value, deposited_at) VALUES (?,?,?,?,?)"
    )
    st.setString(1, sagaId.value)
    st.setString(2, owner)
    st.setString(3, key)
    st.setString(4, value)
    st.setString(5, now)
    try
      st.executeUpdate()
      st.close()
      conn.commit()
      Right(())
    catch
      case e: java.sql.SQLException if e.getMessage.contains("UNIQUE constraint") =>
        conn.rollback()
        st.close()
        Left(s"Key '$key' already owned in pool for saga '${sagaId.value}'")

  /** Deposit all OKV entries for a step as a single transaction (delta). */
  def depositDelta(sagaId: SagaId, delta: Map[String, OkvEntry]): Either[String, Unit] =
    val now = Instant.now().toString
    try
      delta.foreach: (key, entry) =>
        val st = conn.prepareStatement(
          "INSERT INTO saga_pool (saga_id, owner, key, value, deposited_at) VALUES (?,?,?,?,?)"
        )
        st.setString(1, sagaId.value)
        st.setString(2, entry.owner)
        st.setString(3, key)
        st.setString(4, entry.value.toString)
        st.setString(5, now)
        st.executeUpdate()
        st.close()
      conn.commit()
      Right(())
    catch
      case e: java.sql.SQLException if e.getMessage.contains("UNIQUE constraint") =>
        conn.rollback()
        Left(s"Ownership violation in delta for saga '${sagaId.value}': ${e.getMessage}")

  /** Reconstruct the full pool for a saga — used by ZombieHunter. */
  def poolFor(sagaId: SagaId): Map[String, (String, String)] =
    val st = conn.prepareStatement(
      "SELECT owner, key, value FROM saga_pool WHERE saga_id = ? ORDER BY deposited_at ASC"
    )
    st.setString(1, sagaId.value)
    val rs     = st.executeQuery()
    val buffer = scala.collection.mutable.Map.empty[String, (String, String)]
    while rs.next() do
      buffer(rs.getString("key")) = (rs.getString("owner"), rs.getString("value"))
    rs.close(); st.close()
    buffer.toMap

  def close(): Unit = conn.close()

  /** Insert a step with an explicit timestamp — used in tests to simulate TTL expiry. */
  def insertStepAt(sagaId: SagaId, stepId: String, kind: StepKind, status: StepStatus, at: String): Unit =
    val st = conn.prepareStatement(
      "INSERT INTO saga_step (saga_id, step_id, kind, status, started_at, updated_at) VALUES (?,?,?,?,?,?)"
    )
    st.setString(1, sagaId.value)
    st.setString(2, stepId)
    st.setString(3, kind.toString)
    st.setString(4, status.toString)
    st.setString(5, at)
    st.setString(6, at)
    st.executeUpdate()
    st.close()
    conn.commit()
