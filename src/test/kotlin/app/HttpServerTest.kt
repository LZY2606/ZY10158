package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path

class HttpServerTest {

    private fun body(method: String, url: String, payload: String? = null): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (payload != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(payload.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    @Test
    fun `full import, detail, review conflict and rerun flow over http`(@TempDir dir: Path) {
        val db = Database(File(dir.toFile(), "http.sqlite").absolutePath)
        val repo = Repository(db)
        val service = AnalysisService(repo, ManualClock(1000))
        val web = WebServer(service, repo, 0)
        web.start()
        val port = web.boundPort
        try {
            val (healthCode, health) = body("GET", "http://127.0.0.1:$port/api/health")
            assertEquals(200, healthCode)
            assertTrue(health.contains("\"ok\":true"))

            val content = "100 7E0 TX 02 3E 00 00 00 00 00 00\n120 7E8 RX 02 7E 00 00 00 00 00 00\n"
            val payload = Json.stringify(mapOf("name" to "http.cand", "content" to content))
            val (code, text) = body("POST", "http://127.0.0.1:$port/api/captures", payload)
            assertEquals(201, code, text)
            val created = Json.parseObject(text)
            val capture = created["capture"] as Map<*, *>
            val captureId = (capture["id"] as Number).toLong()

            // idempotent re-import
            val (code2, text2) = body("POST", "http://127.0.0.1:$port/api/captures", payload)
            assertEquals(200, code2)
            assertTrue(text2.contains("\"idempotentHit\":true"))

            val (dcode, detailText) = body("GET", "http://127.0.0.1:$port/api/captures/$captureId")
            assertEquals(200, dcode)
            val detail = Json.parseObject(detailText)
            @Suppress("UNCHECKED_CAST")
            val frames = detail["frames"] as List<Map<String, Any?>>
            assertEquals(2, frames.size)
            @Suppress("UNCHECKED_CAST")
            val messages = detail["messages"] as List<Map<String, Any?>>
            assertEquals(2, messages.size)

            // reject frame with optimistic create, then replay create -> 409
            val frameId = (frames[0]["id"] as Number).toLong()
            val reviewPayload = Json.stringify(mapOf("captureId" to captureId, "rejected" to true, "note" to "noise"))
            val (rc, _) = body("POST", "http://127.0.0.1:$port/api/frames/$frameId/review", reviewPayload)
            assertEquals(200, rc)
            val (rc2, conflictText) = body("POST", "http://127.0.0.1:$port/api/frames/$frameId/review", reviewPayload)
            assertEquals(409, rc2)
            assertTrue(conflictText.contains("conflict"))

            // after rejection only the RX message remains
            val (_, afterText) = body("GET", "http://127.0.0.1:$port/api/captures/$captureId")
            val after = Json.parseObject(afterText)
            @Suppress("UNCHECKED_CAST")
            val afterMessages = after["messages"] as List<Map<String, Any?>>
            assertEquals(1, afterMessages.size)
            assertEquals("RX", afterMessages[0]["direction"])
        } finally {
            web.stop(1)
            db.close()
        }
    }
}
