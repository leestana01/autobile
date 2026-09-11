package com.autobile.ai.provider

import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns raw model text into a validated domain object.
 *
 * Shared by every provider so that a response is held to exactly the same standard
 * whether it came from the device or from a server. Nothing reaches an executor
 * without passing extraction, parsing and schema validation in turn.
 */
fun <T : Any> decodeStructured(
    textResult: InferenceResult<String>,
    schema: ResponseSchema<T>,
    tier: RuntimeTier,
    providerId: String,
): InferenceResult<T> {
    fun reject(kind: InferenceErrorKind, message: String) = InferenceResult<T>(
        value = null,
        confidence = 0f,
        tier = tier,
        providerId = providerId,
        rawText = textResult.rawText ?: textResult.value,
        error = InferenceError(kind, message),
        latencyMs = textResult.latencyMs,
    )

    if (!textResult.isSuccess) {
        return reject(
            textResult.error?.kind ?: InferenceErrorKind.UNKNOWN,
            textResult.error?.message ?: "no response",
        )
    }

    val json = JsonExtractor.extract(textResult.value)
        ?: return reject(InferenceErrorKind.PARSE_FAILED, "response was not JSON")

    val parsed = schema.parse(json)
        ?: return reject(InferenceErrorKind.PARSE_FAILED, "response did not match ${schema.name}")

    schema.validate(parsed)?.let { reason ->
        return reject(InferenceErrorKind.PARSE_FAILED, reason)
    }

    // Schemas that ask the model to rate its own certainty get that value; the rest
    // fall back to a neutral score so a caller demanding high certainty still escalates.
    val reported = (json["confidence"] as? JsonPrimitive)?.content?.toFloatOrNull()

    return InferenceResult(
        value = parsed,
        confidence = reported?.coerceIn(0f, 1f) ?: UNRATED_CONFIDENCE,
        tier = tier,
        providerId = providerId,
        rawText = textResult.value,
        latencyMs = textResult.latencyMs,
    )
}

private const val UNRATED_CONFIDENCE = 0.7f
