package app

import java.security.MessageDigest

data class ParsedCapture(
    val name: String,
    val fileSha256: String,
    val frames: List<RawFrame>,
    val directives: List<RestartDirective>,
)

/**
 * Capture text format (`.cand`), one record per line, '#' introduces a comment:
 *
 *   # restart [at=1200] [id=7E8] note...        directive suggesting an ECU restart boundary
 *   1000 7E0 TX 02 10 03 00 00 00 00 00
 *   ^offset_ms  ^CAN_ID(hex) ^TX|RX ^data bytes (hex)
 *
 * Records are ordered by file position; timestamps must be non-decreasing per directive stream.
 */
object CaptureParser {

    fun parse(name: String, content: String): ParsedCapture {
        val digest = MessageDigest.getInstance("SHA-256")
        val sha = Hex.encode(digest.digest(content.toByteArray(Charsets.UTF_8)))

        val frames = ArrayList<RawFrame>()
        val directives = ArrayList<RestartDirective>()
        var lastOffset = -1L

        content.lineSequence().forEachIndexed { lineNo0, rawLine ->
            val lineNo = lineNo0 + 1
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed

            if (line.startsWith("#")) {
                val body = line.removePrefix("#").trim()
                val tokens = body.split(Regex("\\s+"))
                if (tokens.firstOrNull()?.lowercase() == "restart") {
                    var at: Long? = null
                    var scope: Int? = null
                    val noteParts = ArrayList<String>()
                    for (t in tokens.drop(1)) {
                        when {
                            t.startsWith("at=", true) -> at = t.substring(3).toLongOrNull()
                                ?: error("line $lineNo: bad at= value")
                            t.startsWith("id=", true) -> scope = t.substring(3).toIntOrNull(16)
                                ?: error("line $lineNo: bad id= value")
                            else -> noteParts.add(t)
                        }
                    }
                    directives.add(
                        RestartDirective(
                            captureId = 0,
                            offsetMs = at ?: (lastOffset + 1).coerceAtLeast(0),
                            scopeCanId = scope,
                            note = noteParts.joinToString(" "),
                        )
                    )
                }
                return@forEachIndexed
            }

            val parts = line.split(Regex("\\s+"))
            require(parts.size >= 4) { "line $lineNo: expected '<offset_ms> <can_id(hex)> <TX|RX> <data...>'" }
            val offset = parts[0].toLongOrNull()
                ?: error("line $lineNo: offset_ms '${parts[0]}' is not a number")
            require(offset >= lastOffset) { "line $lineNo: timestamps must be non-decreasing (got $offset < $lastOffset)" }
            lastOffset = offset
            val canId = parts[1].toIntOrNull(16)
                ?: error("line $lineNo: CAN id '${parts[1]}' is not hexadecimal")
            val dir = when (parts[2].uppercase()) {
                "TX" -> Direction.TX
                "RX" -> Direction.RX
                else -> error("line $lineNo: direction must be TX or RX, got '${parts[2]}'")
            }
            val data = try {
                Hex.decode(parts.drop(3).joinToString(""))
            } catch (e: IllegalArgumentException) {
                error("line $lineNo: ${e.message}")
            }
            require(data.size in 1..8) { "line $lineNo: classic CAN DLC must be 1..8 bytes (got ${data.size})" }
            frames.add(
                RawFrame(
                    captureId = 0,
                    seq = frames.size,
                    offsetMs = offset,
                    canId = canId,
                    direction = dir,
                    data = data,
                )
            )
        }
        return ParsedCapture(name, sha, frames, directives)
    }
}
