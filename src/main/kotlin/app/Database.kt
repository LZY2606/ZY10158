package app

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class ConflictException(message: String) : RuntimeException(message)

data class StoredMachineRun(
    val id: Long,
    val assemblies: List<Assembly>,
    val diagnostics: List<Diagnostic>,
    val frameAssembly: Map<Long, Long>,
    val generations: List<GenerationInfo>
)

class Database(path: String) {
    val conn: Connection

    init {
        val p = Path.of(path)
        if (p.parent != null) Files.createDirectories(p.parent)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
        }
        conn.autoCommit = false
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA foreign_keys=ON")
            st.executeUpdate("PRAGMA busy_timeout=5000")
        }
        conn.commit()
        migrate()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS capture (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  sha256 TEXT NOT NULL UNIQUE,
                  filename TEXT NOT NULL,
                  imported_at_ms INTEGER NOT NULL,
                  frame_count INTEGER NOT NULL,
                  reset_count INTEGER NOT NULL,
                  published INTEGER NOT NULL DEFAULT 0,
                  latest_run_id INTEGER
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS raw_frame (
                  capture_id INTEGER NOT NULL REFERENCES capture(id),
                  seq INTEGER NOT NULL,
                  can_id INTEGER NOT NULL,
                  ext INTEGER NOT NULL,
                  data BLOB NOT NULL,
                  ts_us INTEGER NOT NULL,
                  direction TEXT NOT NULL,
                  raw_line TEXT NOT NULL,
                  PRIMARY KEY (capture_id, seq)
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS reset_boundary (
                  id INTEGER NOT NULL,
                  capture_id INTEGER NOT NULL REFERENCES capture(id),
                  after_seq INTEGER NOT NULL,
                  ts_us INTEGER NOT NULL,
                  origin TEXT NOT NULL,
                  can_id INTEGER,
                  PRIMARY KEY (capture_id, id)
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS machine_run (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  capture_id INTEGER NOT NULL REFERENCES capture(id),
                  created_at_ms INTEGER NOT NULL,
                  fc_timeout_us INTEGER NOT NULL,
                  cr_timeout_us INTEGER NOT NULL,
                  published INTEGER NOT NULL DEFAULT 0
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS assembly (
                  id INTEGER NOT NULL,
                  run_id INTEGER NOT NULL REFERENCES machine_run(id),
                  capture_id INTEGER NOT NULL,
                  can_id INTEGER NOT NULL,
                  direction TEXT NOT NULL,
                  generation INTEGER NOT NULL,
                  start_frame_seq INTEGER NOT NULL,
                  end_frame_seq INTEGER,
                  status TEXT NOT NULL,
                  payload BLOB NOT NULL,
                  declared_len INTEGER,
                  received_len INTEGER NOT NULL,
                  missing_json TEXT NOT NULL,
                  evidence_json TEXT NOT NULL,
                  start_ts_us INTEGER NOT NULL,
                  end_ts_us INTEGER,
                  error_code TEXT,
                  first_data_frame_seq INTEGER NOT NULL,
                  PRIMARY KEY (run_id, id)
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS frame_assembly (
                  run_id INTEGER NOT NULL,
                  frame_seq INTEGER NOT NULL,
                  assembly_id INTEGER NOT NULL,
                  PRIMARY KEY (run_id, frame_seq)
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS diagnostic (
                  id INTEGER NOT NULL,
                  run_id INTEGER NOT NULL,
                  capture_id INTEGER NOT NULL,
                  code TEXT NOT NULL,
                  severity TEXT NOT NULL,
                  frame_seq INTEGER,
                  can_id INTEGER,
                  direction TEXT,
                  generation INTEGER,
                  message TEXT NOT NULL,
                  PRIMARY KEY (run_id, id)
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS req_resp_link (
                  id INTEGER NOT NULL,
                  run_id INTEGER NOT NULL,
                  capture_id INTEGER NOT NULL,
                  generation INTEGER NOT NULL,
                  request_assembly_id INTEGER,
                  response_assembly_id INTEGER,
                  status TEXT NOT NULL,
                  candidates_json TEXT NOT NULL,
                  service_id INTEGER,
                  nrc INTEGER,
                  PRIMARY KEY (run_id, id)
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS frame_adjudication (
                  capture_id INTEGER NOT NULL,
                  frame_seq INTEGER NOT NULL,
                  rejected INTEGER NOT NULL,
                  reject_reason TEXT,
                  role_override TEXT,
                  version INTEGER NOT NULL,
                  created_at_ms INTEGER NOT NULL,
                  PRIMARY KEY (capture_id, frame_seq)
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS link_adjudication (
                  capture_id INTEGER NOT NULL,
                  link_id INTEGER NOT NULL,
                  chosen_request_assembly_id INTEGER,
                  keep_candidates INTEGER NOT NULL,
                  note TEXT,
                  version INTEGER NOT NULL,
                  created_at_ms INTEGER NOT NULL,
                  PRIMARY KEY (capture_id, link_id)
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS adjudication_conflict (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  capture_id INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  target_key TEXT NOT NULL,
                  expected_version INTEGER,
                  actual_version INTEGER,
                  payload_json TEXT NOT NULL,
                  created_at_ms INTEGER NOT NULL
                )""".trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS adjudication_audit (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  capture_id INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  target_key TEXT NOT NULL,
                  version INTEGER NOT NULL,
                  payload_json TEXT NOT NULL,
                  created_at_ms INTEGER NOT NULL
                )""".trimIndent()
            )
        }
        conn.commit()
    }

    fun close() = conn.close()
}
