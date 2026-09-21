@file:JvmName("WebServerKt")
package app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class WebServer(
    private val port: Int,
    private val service: ViewService,
    private val repo: Repository
) {
    private lateinit var server: HttpServer

    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/", ::route)
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        server.start()
        println("CAN/ISO-TP 回放服务已启动: http://127.0.0.1:$port")
    }

    fun stop() { server.stop(0) }

    private fun route(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            when {
                path == "/api/health" -> json(ex, 200, mapOf("ok" to true))
                path == "/api/captures" && ex.requestMethod == "GET" -> listCaptures(ex)
                path == "/api/captures" && ex.requestMethod == "POST" -> importCapture(ex)
                path.startsWith("/api/captures/") -> routeCapture(ex, path)
                path == "/" || path == "/index.html" -> static(ex, "web/index.html", "text/html; charset=utf-8")
                path.startsWith("/web/") -> static(ex, path.removePrefix("/"), mime(path))
                else -> json(ex, 404, mapOf("error" to "not found: $path"))
            }
        } catch (e: ConflictException) {
            json(ex, 409, mapOf("error" to (e.message ?: "版本冲突"), "conflict" to true))
        } catch (e: IllegalArgumentException) {
            json(ex, 400, mapOf("error" to (e.message ?: "非法请求")))
        } catch (e: Exception) {
            json(ex, 500, mapOf("error" to (e.message ?: "内部错误"), "type" to e.javaClass.simpleName))
        } finally {
            ex.close()
        }
    }

    private fun routeCapture(ex: HttpExchange, path: String) {
        val rest = path.removePrefix("/api/captures/")
        val parts = rest.split("/")
        val captureId = parts[0].toLongOrNull()
            ?: return json(ex, 400, mapOf("error" to "captureId 必须为数字"))
        when {
            parts.size == 1 && ex.requestMethod == "GET" -> state(ex, captureId)
            parts.size == 2 && parts[1] == "reassemble" && ex.requestMethod == "POST" ->
                json(ex, 200, mapOf("runId" to service.recompute(captureId)))
            parts.size == 2 && parts[1] == "adjudications" && ex.requestMethod == "GET" ->
                listAdjudications(ex, captureId)
            parts.size == 2 && parts[1] == "conflicts" && ex.requestMethod == "GET" ->
                json(ex, 200, mapOf("conflicts" to repo.listConflicts(captureId)))
            parts.size == 3 && parts[1] == "frames" && parts[2] == "adjudication" && ex.requestMethod == "PUT" ->
                submitFrameAdjudication(ex, captureId)
            parts.size == 3 && parts[1] == "links" && parts[2] == "adjudication" && ex.requestMethod == "PUT" ->
                submitLinkAdjudication(ex, captureId)
            parts.size == 2 && parts[1] == "reset-boundary" && ex.requestMethod == "POST" ->
                addReset(ex, captureId)
            else -> json(ex, 404, mapOf("error" to "未知 API: $path"))
        }
    }

    private fun listCaptures(ex: HttpExchange) {
        json(ex, 200, mapOf("captures" to repo.listCaptures().map(::captureJson)))
    }

    private fun importCapture(ex: HttpExchange) {
        val query = ex.requestURI.query.orEmpty()
            .split("&").filter { it.isNotEmpty() }
            .associate { val (k, v) = it.split("=", limit = 2); k to v }
        val filename = java.net.URLDecoder.decode(query["filename"] ?: "capture.log", "UTF-8")
        val text = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        require(text.isNotBlank()) { "上传内容为空" }
        val res = repo.importCapture(filename, text)
        val runId = service.recompute(res.captureId)
        json(ex, if (res.alreadyExisted) 200 else 201, mapOf(
            "captureId" to res.captureId, "idempotent" to res.alreadyExisted, "runId" to runId
        ))
    }

    private fun state(ex: HttpExchange, captureId: Long) {
        val view = service.effectiveView(captureId)
            ?: return json(ex, 404, mapOf("error" to "capture 不存在或尚未重组"))
        val playhead = ex.requestURI.query.orEmpty().split("&")
            .firstOrNull { it.startsWith("playhead=") }?.removePrefix("playhead=")?.toIntOrNull()
        json(ex, 200, viewJson(view, playhead))
    }

    private fun listAdjudications(ex: HttpExchange, captureId: Long) {
        val frames = repo.loadFrameAdjudications(captureId).map {
            mapOf(
                "frameSeq" to it.frameSeq, "rejected" to it.rejected,
                "rejectReason" to it.rejectReason, "roleOverride" to it.roleOverride?.name,
                "version" to it.version, "createdAtMs" to it.createdAtMs
            )
        }
        val links = repo.loadLinkAdjudications(captureId).map {
            mapOf(
                "linkId" to it.linkId, "chosenRequestAssemblyId" to it.chosenRequestAssemblyId,
                "keepCandidates" to it.keepCandidates, "note" to it.note,
                "version" to it.version, "createdAtMs" to it.createdAtMs
            )
        }
        json(ex, 200, mapOf("frames" to frames, "links" to links))
    }

    private fun submitFrameAdjudication(ex: HttpExchange, captureId: Long) {
        val body = Json.parse(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
        val version = body.int("version")
        val v = repo.submitFrameAdjudication(
            captureId,
            FrameAdjInput(
                body.long("frameSeq") ?: error("缺少 frameSeq"),
                body.bool("rejected") ?: false,
                body.string("rejectReason"),
                body.string("roleOverride")?.let { Direction.valueOf(it) }
            ),
            version
        )
        val runId = service.recompute(captureId)
        json(ex, 200, mapOf("version" to v, "runId" to runId))
    }

    private fun submitLinkAdjudication(ex: HttpExchange, captureId: Long) {
        val body = Json.parse(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
        val version = body.int("version")
        val v = repo.submitLinkAdjudication(
            captureId,
            LinkAdjInput(
                body.long("linkId") ?: error("缺少 linkId"),
                body.long("chosenRequestAssemblyId"),
                body.bool("keepCandidates") ?: false,
                body.string("note")
            ),
            version
        )
        json(ex, 200, mapOf("version" to v))
    }

    private fun addReset(ex: HttpExchange, captureId: Long) {
        val body = Json.parse(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
        val afterSeq = body.long("afterSeq") ?: error("缺少 afterSeq")
        val canId = body.long("canId")?.toInt()
        val id = repo.addHumanReset(captureId, afterSeq, canId)
        val runId = service.recompute(captureId)
        json(ex, 200, mapOf("resetId" to id, "runId" to runId))
    }

    private fun static(ex: HttpExchange, resource: String, contentType: String) {
        val input = javaClass.classLoader.getResourceAsStream(resource)
            ?: return json(ex, 404, mapOf("error" to "资源不存在: $resource"))
        val bytes = input.use { it.readBytes() }
        ex.responseHeaders.set("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun mime(path: String) = when (path.substringAfterLast('.', "")) {
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    private fun json(ex: HttpExchange, code: Int, body: Any?) {
        val bytes = Json.write(body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
