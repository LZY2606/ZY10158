package app

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class Database(val path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        Class.forName("org.sqlite.JDBC")
        // journal_mode cannot be changed inside a transaction, so run pragmas in auto-commit mode.
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("PRAGMA busy_timeout=5000")
        }
        conn.autoCommit = false
        migrate()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS captures (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    file_sha256 TEXT NOT NULL UNIQUE,
                    frame_count INTEGER NOT NULL,
                    imported_at_ms INTEGER NOT NULL,
                    review_version INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS raw_frames (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    capture_id INTEGER NOT NULL REFERENCES captures(id),
                    seq INTEGER NOT NULL,
                    offset_ms INTEGER NOT NULL,
                    can_id INTEGER NOT NULL,
                    direction TEXT NOT NULL,
                    data_hex TEXT NOT NULL,
                    UNIQUE(capture_id, seq)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS restart_directives (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    capture_id INTEGER NOT NULL REFERENCES captures(id),
                    offset_ms INTEGER NOT NULL,
                    scope_can_id INTEGER,
                    note TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS parser_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    capture_id INTEGER NOT NULL REFERENCES captures(id),
                    run_number INTEGER NOT NULL,
                    started_at_ms INTEGER NOT NULL,
                    finished_at_ms INTEGER NOT NULL,
                    review_version_at_run INTEGER NOT NULL,
                    published INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(capture_id, run_number)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id INTEGER NOT NULL REFERENCES parser_runs(id),
                    capture_id INTEGER NOT NULL,
                    local_key TEXT NOT NULL,
                    can_id INTEGER NOT NULL,
                    direction TEXT NOT NULL,
                    gen INTEGER NOT NULL,
                    first_frame_seq INTEGER NOT NULL,
                    last_frame_seq INTEGER NOT NULL,
                    start_ms INTEGER NOT NULL,
                    end_ms INTEGER NOT NULL,
                    multi_frame INTEGER NOT NULL,
                    expected_length INTEGER,
                    payload_hex TEXT NOT NULL,
                    missing_indices TEXT NOT NULL,
                    received_frame_seqs TEXT NOT NULL,
                    status TEXT NOT NULL,
                    status_detail TEXT NOT NULL DEFAULT '',
                    paired_message_local_key TEXT,
                    sid INTEGER,
                    candidate_local_keys TEXT NOT NULL DEFAULT '',
                    UNIQUE(run_id, local_key)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS diagnostics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id INTEGER NOT NULL REFERENCES parser_runs(id),
                    capture_id INTEGER NOT NULL,
                    code TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    offset_ms INTEGER,
                    can_id INTEGER,
                    direction TEXT,
                    gen INTEGER,
                    frame_seq INTEGER,
                    message_local_key TEXT,
                    detail TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS frame_reviews (
                    frame_id INTEGER PRIMARY KEY,
                    rejected INTEGER NOT NULL DEFAULT 0,
                    role_override TEXT,
                    note TEXT NOT NULL DEFAULT '',
                    version INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS attribution_reviews (
                    message_local_key TEXT PRIMARY KEY,
                    capture_id INTEGER NOT NULL,
                    choice_local_key TEXT,
                    keep_both INTEGER NOT NULL DEFAULT 0,
                    note TEXT NOT NULL DEFAULT '',
                    version INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS restart_marks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    capture_id INTEGER NOT NULL REFERENCES captures(id),
                    offset_ms INTEGER NOT NULL,
                    scope_can_id INTEGER,
                    note TEXT NOT NULL DEFAULT '',
                    created_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
        conn.commit()
    }

    fun close() = conn.close()
}

fun ResultSet.long(opt: String): Long? = getLong(opt).let { if (wasNull()) null else it }
fun ResultSet.int(opt: String): Int? = getInt(opt).let { if (wasNull()) null else it }
