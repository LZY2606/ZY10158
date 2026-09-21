package app

object Hex {
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString()
    }

    fun encodeCompact(bytes: ByteArray): String = encode(bytes)

    fun decode(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\t", "")
        require(clean.length % 2 == 0) { "hex string must have even length: '$s'" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "non-hex character in '$s'" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** Tiny zero-dependency JSON implementation: values are Map/List/String/Long/Double/Boolean/null. */
@Suppress("UNCHECKED_CAST")
object Json {
    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Number -> sb.append(v.toString())
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is ByteArray -> write(sb, v.toList())
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '' -> sb.append("\\f")
            else -> if (c.code < 0x20) {
                sb.append("\\u")
                val h = c.code.toString(16)
                repeat(4 - h.length) { sb.append('0') }
                sb.append(h)
            } else sb.append(c)
        }
        sb.append('"')
    }

    fun parse(input: String): Any? {
        val p = Parser(input)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        require(p.atEnd()) { "trailing characters at ${p.pos}" }
        return v
    }

    fun parseObject(input: String): Map<String, Any?> = parse(input) as Map<String, Any?>

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && Character.isWhitespace(s[pos])) pos++
        }

        fun readValue(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end of json" }
            return when (s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                require(s[pos] == ':') { "expected ':' at $pos" }
                pos++
                m[key] = readValue()
                skipWs()
                when (s[pos]) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return m }
                    else -> error("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                when (s[pos]) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return list }
                    else -> error("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            require(s[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> error("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readBoolean(): Boolean {
            return if (s.startsWith("true", pos)) { pos += 4; true }
            else { require(s.startsWith("false", pos)); pos += 5; false }
        }

        private fun readNull(): Any? {
            require(s.startsWith("null", pos))
            pos += 4
            return null
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            var isDouble = false
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c in '0'..'9' -> pos++
                    c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' -> { isDouble = true; pos++ }
                    else -> break
                }
            }
            val token = s.substring(start, pos)
            return if (isDouble) token.toDouble() else token.toLong()
        }
    }
}
