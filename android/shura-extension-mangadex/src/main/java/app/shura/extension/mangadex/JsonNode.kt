package app.shura.extension.mangadex

/**
 * A deliberately small JSON reader.
 *
 * The extension ships as an APK that must load inside Shura's runtime without
 * pulling anything extra in, so it parses the three shapes it needs rather than
 * declaring a JSON dependency. It is not a general parser and does not try to be.
 */
sealed class JsonNode {
    data class Obj(val fields: Map<String, JsonNode>) : JsonNode() {
        fun str(name: String): String? = (fields[name] as? Str)?.value
        fun num(name: String): Double? = (fields[name] as? Num)?.value
        fun arr(name: String): List<JsonNode>? = (fields[name] as? Arr)?.items
        fun obj(name: String): Obj? = fields[name] as? Obj
    }
    data class Arr(val items: List<JsonNode>) : JsonNode()
    data class Str(val value: String) : JsonNode()
    data class Num(val value: Double) : JsonNode()
    data class Bool(val value: Boolean) : JsonNode()
    object Null : JsonNode()

    companion object {
        fun parse(text: String): JsonNode = Reader(text).run { skipSpace(); value() }
    }
}

/** The objects inside a JSON array, skipping anything that is not one. */
fun List<JsonNode>.objects(): List<JsonNode.Obj> = filterIsInstance<JsonNode.Obj>()

private class Reader(private val text: String) {
    private var at = 0

    fun skipSpace() { while (at < text.length && text[at].isWhitespace()) at++ }

    fun value(): JsonNode {
        skipSpace()
        check(at < text.length) { "unexpected end of JSON" }
        return when (text[at]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JsonNode.Str(string())
            't', 'f' -> bool()
            'n' -> { expect("null"); JsonNode.Null }
            else -> number()
        }
    }

    private fun obj(): JsonNode.Obj {
        expect("{")
        val fields = LinkedHashMap<String, JsonNode>()
        skipSpace()
        if (text[at] == '}') { at++; return JsonNode.Obj(fields) }
        while (true) {
            skipSpace()
            val key = string()
            skipSpace(); expect(":")
            fields[key] = value()
            skipSpace()
            when (text[at]) {
                ',' -> at++
                '}' -> { at++; return JsonNode.Obj(fields) }
                else -> error("expected , or } at $at")
            }
        }
    }

    private fun arr(): JsonNode.Arr {
        expect("[")
        val items = mutableListOf<JsonNode>()
        skipSpace()
        if (text[at] == ']') { at++; return JsonNode.Arr(items) }
        while (true) {
            items += value()
            skipSpace()
            when (text[at]) {
                ',' -> at++
                ']' -> { at++; return JsonNode.Arr(items) }
                else -> error("expected , or ] at $at")
            }
        }
    }

    private fun string(): String {
        expect("\"")
        val out = StringBuilder()
        while (at < text.length) {
            when (val c = text[at++]) {
                '"' -> return out.toString()
                '\\' -> when (val esc = text[at++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'u' -> { out.append(text.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                    else -> out.append(esc)
                }
                else -> out.append(c)
            }
        }
        error("unterminated string")
    }

    private fun number(): JsonNode {
        val start = at
        while (at < text.length && (text[at].isDigit() || text[at] in "-+.eE")) at++
        return JsonNode.Num(text.substring(start, at).toDouble())
    }

    private fun bool(): JsonNode = if (text.startsWith("true", at)) {
        at += 4; JsonNode.Bool(true)
    } else { expect("false"); JsonNode.Bool(false) }

    private fun expect(needle: String) {
        check(text.startsWith(needle, at)) { "expected $needle at $at" }
        at += needle.length
    }
}
