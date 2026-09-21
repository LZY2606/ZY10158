package app

/* 极简 JSON：支持 object/array/string/number/bool/null，全部数字按 Long/Double 解析。 */

class JsonObject(val map: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    fun obj(key: String): JsonObject? = (map[key] as? Map<String, Any?>)?.let { JsonObject(it) }
    fun string(key: String): String? = map[key] as? String
    fun int(key: String): Int? = when (val v = map[key]) {
        is Number -> v.toInt(); is String -> v.toIntOrNull(); else -> null
    }
    fun long(key: String): Long? = when (val v = map[key]) {
        is Number -> v.toLong(); is String -> v.toLongOrNull(); else -> null
    }
    fun bool(key: String): Boolean? = map[key] as? Boolean
    @Suppress("UNCHECKED_CAST")
    fun array(key: String): List<Any?>? = map[key] as? List<Any?>
    fun contains(key: String) = map.containsKey(key)
}

object Json {
    fun parse(input: String): JsonObject {
        val v = Parser(input).parseValue()
        @Suppress("UNCHECKED_CAST")
        return JsonObject(v as Map<String, Any?>)
    }

    fun write(value: Any?): String = StringBuilder().apply { writeValue(this, value) }.toString()

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Number -> sb.append(v.toString())
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    writeString(sb, e.key.toString())
                    sb.append(':')
                    writeValue(sb, e.value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                v.forEachIndexed { i, e -> if (i > 0) sb.append(','); writeValue(sb, e) }
                sb.append(']')
            }
            is ByteArray -> writeValue(sb, v.joinToString("") { "%02X".format(it) })
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t");
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun parseValue(): Any? {
            skipWs()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }
        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) throw IllegalArgumentException("JSON 解析错误 @$i: 期望 $lit")
            i += lit.length
        }
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun parseObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = parseString()
                skipWs()
                if (s[i] != ':') throw IllegalArgumentException("JSON: 期望 ':' @$i")
                i++
                m[k] = parseValue()
                skipWs()
                when (s[i]) { ',' -> { i++; continue }
                    '}' -> { i++; break }
                    else -> throw IllegalArgumentException("JSON: 期望 ',' 或 '}' @$i")
                }
            }
            return m
        }
        private fun parseArray(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(parseValue())
                skipWs()
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; break }
                    else -> throw IllegalArgumentException("JSON: 期望 ',' 或 ']' @$i")
                }
            }
            return l
        }
        private fun parseString(): String {
            if (s[i] != '"') throw IllegalArgumentException("JSON: 期望字符串 @$i")
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('')
                            'u' -> {
                                val hex = s.substring(i, i + 4); i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("JSON: 非法转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        private fun parseBool(): Boolean =
            if (s.startsWith("true", i)) { i += 4; true }
            else { expect("false"); false }
        private fun parseNumber(): Any? {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val tok = s.substring(start, i)
            return if (tok.any { it in ".eE" }) tok.toDouble() else tok.toLong()
        }
    }
}
