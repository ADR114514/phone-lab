package com.mobilelab.core.json

/**
 * Minimal JSON serialiser. Avoids a third party dependency for the handful of
 * manifest/report files written by the apps.
 */
class JsonObject {
    private val members = LinkedHashMap<String, String>()

    fun put(key: String, value: Any?): JsonObject {
        members[key] = encode(value)
        return this
    }

    fun putRaw(key: String, rawJson: String): JsonObject {
        members[key] = rawJson
        return this
    }

    fun isEmpty(): Boolean = members.isEmpty()

    fun toJson(): String = members.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"" + escape(k) + "\":" + v
    }

    companion object {
        fun escape(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (ch in s) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun encode(value: Any?): String = when (value) {
            null -> "null"
            is String -> "\"" + escape(value) + "\""
            is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
            is Float -> number(value.toDouble())
            is Double -> number(value)
            is JsonObject -> value.toJson()
            is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
            is Array<*> -> value.joinToString(",", "[", "]") { encode(it) }
            else -> "\"" + escape(value.toString()) + "\""
        }

        private fun number(v: Double): String =
            if (v.isNaN() || v.isInfinite()) "null" else String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
    }
}
