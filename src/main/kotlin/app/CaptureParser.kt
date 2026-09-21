package app

import java.security.MessageDigest

/*
支持的行格式（# 为注释，空行忽略）：

  1) 扩展格式：
       <ts> <id> <DIR> <hex...>
       例： 0000.120000 7E0 REQ 02 10 03
       DIR ∈ {REQ, RESP}；也允许 REQUEST/RESPONSE/TX/RX（TX=REQ, RX=RESP）

  2) candump 兼容格式：
       <ts>  <bus>  <id>#<hex...>          （标准帧，按 UDS 约定判向）
       <ts>  <bus>  <id>##R<flags><hex>    （扩展帧，仅取数据；本工具不分析远程帧）
       例： (1727000000.120000) can0 7E0#021003

  3) 复位边界（ECU 重启）：
       #RESET <ts> [id=<hex>]
       若给定 id 则只对该 CAN ID 生效，否则对全部 ECU ID 生效。
*/

data class ParsedCapture(
    val sha256: String,
    val frames: List<RawFrame>,
    val resets: List<ResetBoundary>
)

class ParseException(message: String, val lineNo: Int) : RuntimeException("第 ${lineNo} 行: $message")

object CaptureParser {

    private val resetRegex = Regex("""^\s*#\s*RESET\s+(\d+(?:\.\d+)?)\s*(?:id=0x([0-9A-Fa-f]+))?\s*$""", RegexOption.IGNORE_CASE)

    fun parse(text: String, captureId: Long = 0L): ParsedCapture {
        val sha = sha256(text)
        val frames = ArrayList<RawFrame>()
        val resets = ArrayList<ResetBoundary>()
        var resetSeq = 0L
        var frameSeq = 0L

        text.lineSequence().forEachIndexed { idx, rawLine0 ->
            val lineNo = idx + 1
            val rawLine = rawLine0.trim()
            if (rawLine.isEmpty() || rawLine.startsWith("#")) {
                resetRegex.matchEntire(rawLine)?.let { m ->
                    val ts = parseTimestamp(m.groupValues[1], lineNo)
                    val canId = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt(16)
                    resets.add(ResetBoundary(resetSeq++, captureId, frameSeq - 1, ts, "fixture", canId))
                }
                return@forEachIndexed
            }

            if (rawLine.contains('#')) {
                frames.add(parseCandump(rawLine, captureId, frameSeq, lineNo))
            } else {
                frames.add(parseExtended(rawLine, captureId, frameSeq, lineNo))
            }
            frameSeq++
        }
        return ParsedCapture(sha, frames, resets)
    }

    private fun parseExtended(line: String, captureId: Long, seq: Long, lineNo: Int): RawFrame {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) throw ParseException("期望 '<ts> <id> <DIR> <hex...>'", lineNo)
        val ts = parseTimestamp(parts[0], lineNo)
        val idTok = parts[1].removePrefix("0x").removePrefix("0X")
        val ext = idTok.length > 3
        val canId = idTok.toIntOrNull(16) ?: throw ParseException("非法 CAN ID: ${parts[1]}", lineNo)
        if (ext && canId > 0x1FFFFFFF) throw ParseException("扩展 ID 超出 29bit", lineNo)
        if (!ext && canId > 0x7FF) throw ParseException("ID 超过 11bit，请使用 8 位十六进制扩展格式", lineNo)

        val dirTok = parts[2].uppercase()
        val dir = when (dirTok) {
            "REQ", "REQUEST", "TX" -> Direction.REQ
            "RESP", "RESPONSE", "RX" -> Direction.RESP
            else -> throw ParseException("非法方向 '$dirTok'（允许 REQ/RESP）", lineNo)
        }
        val data = parseDataBytes(parts.drop(3), lineNo)
        return RawFrame(captureId, seq, canId, ext, data, ts, dir, line)
    }

    private val candumpRegex = Regex(
        """^\s*(?:\((\d+(?:\.\d+)?)\)\s*)?[\w-]+\s+([0-9A-Fa-f]+)#(#R?[0-9]*)?([0-9A-Fa-f]*)\s*$"""
    )

    private fun parseCandump(line: String, captureId: Long, seq: Long, lineNo: Int): RawFrame {
        val m = candumpRegex.matchEntire(line)
            ?: throw ParseException("无法识别的 candump 行", lineNo)
        val tsStr = m.groupValues[1].ifEmpty { "0" }
        val ts = parseTimestamp(tsStr, lineNo)
        val idTok = m.groupValues[2]
        val ext = idTok.length > 3
        val canId = idTok.toInt(16)
        val flags = m.groupValues[3]
        if (flags.startsWith("##R")) throw ParseException("远程帧不参与 ISO-TP 重组", lineNo)
        val payloadTok = m.groupValues[4]
        if (payloadTok.length % 2 != 0) throw ParseException("数据区十六进制长度为奇数", lineNo)
        val data = parseDataBytes(payloadTok.chunked(2), lineNo)
        return RawFrame(captureId, seq, canId, ext, data, ts, guessDirection(canId), line)
    }

    /** 简化 UDS 约定：0x700..0x7DF 中偶数（物理/功能请求 ID）为 REQ，对应 +8 为 RESP。 */
    fun guessDirection(canId: Int): Direction =
        if (canId in 0x700..0x7E7 && (canId and 1) == 0) Direction.REQ else Direction.RESP

    fun peerCanId(canId: Int): Int? {
        return when {
            canId in 0x700..0x7E7 && (canId and 1) == 0 -> canId + 8
            canId in 0x708..0x7EF && (canId and 0x008) == 0x008 -> canId - 8
            else -> null
        }
    }

    private fun parseDataBytes(tokens: List<String>, lineNo: Int): ByteArray {
        if (tokens.isEmpty()) throw ParseException("缺少数据字节", lineNo)
        val joined = tokens.joinToString("") { it.removePrefix("0x") }
        if (joined.any { it.digitToIntOrNull(16) == null })
            throw ParseException("数据含非十六进制字符: $joined", lineNo)
        if (joined.length % 2 != 0) throw ParseException("数据十六进制长度为奇数", lineNo)
        if (joined.length > 64) throw ParseException("单帧数据超过 64 字节（CAN FD 最大 64）", lineNo)
        return ByteArray(joined.length / 2) { joined.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun parseTimestamp(tok: String, lineNo: Int): Long {
        // 支持 秒.小数（candump）与 毫秒/微秒整数式 0000.120000；统一按“秒.微秒”解析。
        return if (tok.contains('.')) {
            val (a, b) = tok.split('.', limit = 2)
            val secs = a.toLongOrNull() ?: throw ParseException("非法时间戳: $tok", lineNo)
            val frac = b
            if (frac.length > 6) throw ParseException("时间戳小数精度超过微秒: $tok", lineNo)
            secs * 1_000_000L + frac.padEnd(6, '0').toLong()
        } else {
            tok.toLongOrNull()?.times(1_000_000L) ?: throw ParseException("非法时间戳: $tok", lineNo)
        }
    }

    fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
