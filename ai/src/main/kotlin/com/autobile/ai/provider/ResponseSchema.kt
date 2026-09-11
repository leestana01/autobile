package com.autobile.ai.provider

import com.autobile.core.common.AutobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A response contract the model is asked to satisfy, together with the parser and the
 * validator that decide whether it actually did.
 *
 * Model output is treated as an untrusted string: [example] is rendered into the
 * prompt to constrain the shape, [parse] turns whatever came back into a domain
 * object, and [validate] rejects values that are syntactically fine but semantically
 * impossible. Only output that survives all three is allowed to drive an action.
 */
class ResponseSchema<T : Any>(
    val name: String,
    /** A concrete example of a valid response, embedded verbatim in the prompt. */
    val example: String,
    /** Field-by-field description of the contract, embedded in the prompt. */
    val fieldGuide: String,
    private val parser: (JsonObject) -> T?,
    private val validator: (T) -> String? = { null },
) {
    /** Parses an already-extracted JSON object, returning null if it does not conform. */
    fun parse(json: JsonObject): T? = runCatching { parser(json) }.getOrNull()

    /** Returns null when [value] is acceptable, or a human-readable reason when it is not. */
    fun validate(value: T): String? = runCatching { validator(value) }.getOrElse { it.message ?: "validation error" }

    /**
     * The instruction block appended to every prompt using this schema.
     *
     * Kept deliberately terse: on-device models have small context windows, and a long
     * preamble costs tokens that are better spent on the screen description.
     */
    fun promptContract(): String = buildString {
        append("Reply with JSON only. No prose, no markdown fences.\n")
        append("Fields:\n")
        append(fieldGuide.trimEnd())
        append("\nExample:\n")
        append(example.trim())
    }
}

/**
 * Extracts a JSON object from raw model output.
 *
 * Smaller models routinely wrap JSON in prose or markdown fences, and occasionally
 * emit trailing commas or single quotes. Recovering from those locally is worth doing:
 * every response salvaged here is a cloud escalation that does not happen.
 */
object JsonExtractor {

    fun extract(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        val candidates = buildList {
            stripFences(raw)?.let { add(it) }
            add(raw)
            balancedObject(raw)?.let { add(it) }
        }
        for (candidate in candidates) {
            parseObject(candidate)?.let { return it }
            parseObject(repair(candidate))?.let { return it }
        }
        return null
    }

    private fun parseObject(text: String): JsonObject? = runCatching {
        AutobileJson.parseToJsonElement(text.trim()) as? JsonObject
    }.getOrNull()

    private fun stripFences(raw: String): String? {
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        return fence.find(raw)?.groupValues?.get(1)?.trim()
    }

    /** Returns the first brace-balanced object in [raw], ignoring braces inside strings. */
    private fun balancedObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun repair(text: String): String = text
        .trim()
        .removePrefix("json")
        .replace(Regex(",\\s*([}\\]])"), "$1")
        .replace(Regex("(?<=[{,\\s])'([^'\\n]*)'\\s*:"), "\"$1\":")
        .replace(Regex(":\\s*'([^'\\n]*)'"), ": \"$1\"")
        .trim()
}

/** Reads a field leniently: absent, null and wrong-typed values all yield the default. */
fun JsonObject.stringOr(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: default

fun JsonObject.floatOr(key: String, default: Float = 0f): Float =
    stringOr(key).toFloatOrNull() ?: default

fun JsonObject.intOr(key: String, default: Int = 0): Int =
    stringOr(key).toIntOrNull() ?: stringOr(key).toFloatOrNull()?.toInt() ?: default

fun JsonObject.boolOr(key: String, default: Boolean = false): Boolean =
    when (stringOr(key).lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> default
    }

fun JsonObject.stringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
}

fun JsonObject.objectList(key: String): List<JsonObject> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.filterIsInstance<JsonObject>()
}
