package app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class WebServer(
    private val service: AnalysisService,
    private val repo: Repository,
    port: Int,
    host: String = "127.0.0.1",
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    val boundPort: Int get() = server.address.port

    init {
        server.createContext("/", RootHandler())
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
    }

    fun start() = server.start()
    fun stop(delaySeconds: Int = 0) = server.stop(delaySeconds)

    private inner class RootHandler : HttpHandler {
        override fun handle(ex: HttpExchange) {
            try {
                route(ex)
            } catch (e: ConflictException) {
                writeJson(ex, 409, linkedMapOf("error" to "conflict", "message" to (e.message ?: "")))
            } catch (e: IllegalArgumentException) {
                writeJson(ex, 400, linkedMapOf("error" to "bad_request", "message" to (e.message ?: "")))
            } catch (e: Exception) {
                e.printStackTrace()
                writeJson(ex, 500, linkedMapOf("error" to "internal", "message" to (e.message ?: e.javaClass.simpleName)))
            } finally {
                ex.close()
            }
        }

        private fun route(ex: HttpExchange) {
            val path = ex.requestURI.path
            val method = ex.requestMethod
            when {
                method == "GET" && path == "/" -> serveStatic(ex, "web/index.html", "text/html; charset=utf-8")
                method == "GET" && path == "/app.js" -> serveStatic(ex, "web/app.js", "application/javascript; charset=utf-8")
                method == "GET" && path == "/styles.css" -> serveStatic(ex, "web/styles.css", "text/css; charset=utf-8")
                method == "GET" && path == "/api/health" ->
                    writeJson(ex, 200, linkedMapOf(
                        "ok" to true,
                        "serverTimeMs" to service.clock.nowMs(),
                        "clockMode" to if (service.clock is ManualClock) "MANUAL" else "SYSTEM"))
                method == "POST" && path == "/api/clock" -> setClock(ex)

                method == "GET" && path == "/api/captures" -> listCaptures(ex)
                method == "POST" && path == "/api/captures" -> importCapture(ex)
                method == "GET" && path.matches(Regex("/api/captures/\\d+")) -> captureDetail(ex, path.substringAfterLast('/').toLong())
                method == "POST" && path.matches(Regex("/api/captures/\\d+/rerun")) -> rerun(ex, path.split('/')[3].toLong())

                method == "GET" && path.matches(Regex("/api/captures/\\d+/restart-marks")) ->
                    listMarks(ex, path.split('/')[3].toLong())
                method == "POST" && path.matches(Regex("/api/captures/\\d+/restart-marks")) ->
                    addMark(ex, path.split('/')[3].toLong())
                method == "DELETE" && path.matches(Regex("/api/captures/\\d+/restart-marks/\\d+")) ->
                    deleteMark(ex, path.split('/')[3].toLong(), path.split('/')[5].toLong())

                method == "POST" && path.matches(Regex("/api/frames/\\d+/review")) ->
                    upsertFrameReview(ex, path.split('/')[3].toLong())
                method == "POST" && path.matches(Regex("/api/captures/\\d+/attribution")) ->
                    upsertAttribution(ex, path.split('/')[3].toLong())

                else -> writeJson(ex, 404, linkedMapOf("error" to "not_found", "path" to path))
            }
        }
    }

    private fun listCaptures(ex: HttpExchange) {
        val list = repo.listCaptures().map { c -> ApiDtos.capture(c, repo.listRuns(c.id)) }
        writeJson(ex, 200, linkedMapOf("captures" to list, "serverTimeMs" to service.clock.nowMs()))
    }

    private fun importCapture(ex: HttpExchange) {
        val body = readBody(ex)
        val obj = Json.parseObject(body)
        val name = (obj["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("name is required")
        val content = obj["content"] as? String
            ?: throw IllegalArgumentException("content (.cand text) is required")
        val (capture, created) = service.import(name, content)
        writeJson(ex, if (created) 201 else 200, linkedMapOf(
            "capture" to ApiDtos.capture(capture, repo.listRuns(capture.id)),
            "idempotentHit" to !created
        ))
    }

    private fun captureDetail(ex: HttpExchange, captureId: Long) {
        val capture = repo.getCapture(captureId) ?: throw IllegalArgumentException("capture not found")
        val frames = repo.loadFrames(captureId)
        val directives = repo.loadDirectives(captureId)
        val marks = repo.listRestartMarks(captureId)
        val reviews = frames.mapNotNull { repo.getFrameReview(it.id) }.associateBy { it.frameId }

        val analyzer = Analyzer(captureId)
        val boundaries = analyzer.collectBoundariesPublic(directives, marks)
        val effective = analyzer.assignGenerationsPublic(frames, reviews, boundaries)
        val gens = effective.associate { it.frame.id to it.gen }
        val effDirs = effective.associate { it.frame.id to it.effectiveDirection }

        val run = repo.latestPublishedRun(captureId)
        val messages = run?.let { repo.loadMessages(it.id) } ?: emptyList()
        val diagnostics = run?.let { repo.loadDiagnostics(it.id) } ?: emptyList()

        val detail = ApiDtos.captureDetail(
            capture, repo.listRuns(captureId), frames, reviews, directives, marks,
            gens, effDirs, run, messages, diagnostics
        ).toMutableMap()

        // Attach L2 attribution resolutions to ambiguous message views.
        val attributionReviews = service.run { loadAttributionReviewsPublic(captureId) }
        val idToKey = messages.associate { it.id to it.localKey }
        @Suppress("UNCHECKED_CAST")
        val ambiguities = (detail["ambiguities"] as List<Map<String, Any?>>).map { view ->
            val key = view["messageLocalKey"] as String
            val review = attributionReviews[key]
            if (review == null) view
            else view + linkedMapOf(
                "review" to linkedMapOf(
                    "choiceLocalKey" to review.choiceLocalKey,
                    "keepBoth" to review.keepBoth,
                    "note" to review.note,
                    "version" to review.version
                )
            )
        }
        detail["ambiguities"] = ambiguities
        writeJson(ex, 200, detail)
    }

    private fun rerun(ex: HttpExchange, captureId: Long) {
        repo.getCapture(captureId) ?: throw IllegalArgumentException("capture not found")
        val run = service.rerun(captureId)
        writeJson(ex, 200, linkedMapOf("run" to ApiDtos.runSummary(run)))
    }

    private fun listMarks(ex: HttpExchange, captureId: Long) {
        repo.getCapture(captureId) ?: throw IllegalArgumentException("capture not found")
        writeJson(ex, 200, linkedMapOf("restartMarks" to repo.listRestartMarks(captureId).map { ApiDtos.mark(it) }))
    }

    private fun addMark(ex: HttpExchange, captureId: Long) {
        repo.getCapture(captureId) ?: throw IllegalArgumentException("capture not found")
        val obj = Json.parseObject(readBody(ex))
        val offset = (obj["offsetMs"] as? Number)?.toLong() ?: throw IllegalArgumentException("offsetMs required")
        val scope = (obj["scopeCanId"] as? String)?.let { it.toIntOrNull(16) ?: throw IllegalArgumentException("scopeCanId must be hex") }
        val note = obj["note"] as? String ?: ""
        val mark = repo.addRestartMark(captureId, offset, scope, note, service.clock.nowMs())
        service.rerun(captureId)
        writeJson(ex, 201, linkedMapOf("restartMark" to ApiDtos.mark(mark)))
    }

    private fun deleteMark(ex: HttpExchange, captureId: Long, markId: Long) {
        repo.deleteRestartMark(captureId, markId)
        service.rerun(captureId)
        writeJson(ex, 200, linkedMapOf("deleted" to markId))
    }

    private fun upsertFrameReview(ex: HttpExchange, frameId: Long) {
        val obj = Json.parseObject(readBody(ex))
        val captureId = (obj["captureId"] as? Number)?.toLong() ?: throw IllegalArgumentException("captureId required")
        val rejected = obj["rejected"] as? Boolean ?: false
        val role = (obj["roleOverride"] as? String)?.let {
            try { Direction.valueOf(it) } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("roleOverride must be TX or RX")
            }
        }
        val note = obj["note"] as? String ?: ""
        val expectedVersion = (obj["expectedVersion"] as? Number)?.toLong()
        val saved = repo.upsertFrameReview(frameId, captureId, rejected, role, note, expectedVersion, service.clock.nowMs())
        service.rerun(captureId)
        writeJson(ex, 200, linkedMapOf(
            "frameReview" to linkedMapOf(
                "frameId" to saved.frameId, "rejected" to saved.rejected,
                "roleOverride" to saved.roleOverride?.name, "note" to saved.note,
                "version" to saved.version
            )
        ))
    }

    private fun upsertAttribution(ex: HttpExchange, captureId: Long) {
        val obj = Json.parseObject(readBody(ex))
        val localKey = obj["messageLocalKey"] as? String ?: throw IllegalArgumentException("messageLocalKey required")
        val choice = obj["choiceLocalKey"] as? String
        val keepBoth = obj["keepBoth"] as? Boolean ?: false
        val note = obj["note"] as? String ?: ""
        val expectedVersion = (obj["expectedVersion"] as? Number)?.toLong()
        val saved = repo.upsertAttributionReview(
            captureId, localKey, choice, keepBoth, note, expectedVersion, service.clock.nowMs()
        )
        service.rerun(captureId)
        writeJson(ex, 200, linkedMapOf(
            "attributionReview" to linkedMapOf(
                "messageLocalKey" to saved.messageLocalKey,
                "choiceLocalKey" to saved.choiceLocalKey, "keepBoth" to saved.keepBoth,
                "note" to saved.note, "version" to saved.version
            )
        ))
    }

    private fun setClock(ex: HttpExchange) {
        val obj = Json.parseObject(readBody(ex))
        val mode = (obj["mode"] as? String) ?: "MANUAL"
        when (mode.uppercase()) {
            "MANUAL" -> {
                val seed = (obj["setMs"] as? Number)?.toLong()
                val manual = service.useManualClock(seed)
                val advance = (obj["advanceMs"] as? Number)?.toLong()
                if (advance != null) manual.advance(advance)
                writeJson(ex, 200, linkedMapOf("clockMode" to "MANUAL", "nowMs" to manual.nowMs()))
            }
            "SYSTEM" -> {
                service.useSystemClock()
                writeJson(ex, 200, linkedMapOf("clockMode" to "SYSTEM", "nowMs" to service.clock.nowMs()))
            }
            else -> throw IllegalArgumentException("mode must be MANUAL or SYSTEM")
        }
    }

    private fun serveStatic(ex: HttpExchange, resource: String, contentType: String) {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readAllBytes() }
            ?: return writeJson(ex, 404, linkedMapOf("error" to "not_found"))
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun readBody(ex: HttpExchange): String =
        ex.requestBody.use { it.readAllBytes() }.toString(StandardCharsets.UTF_8)

    private fun writeJson(ex: HttpExchange, status: Int, body: Any?) {
        val bytes = Json.stringify(body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
