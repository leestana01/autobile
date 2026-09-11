package com.autobile.ai.provider

import android.graphics.Bitmap
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier

/**
 * The contract every inference backend implements, whether it runs on the device's
 * neural engine, in a bundled local model, or behind a network call.
 *
 * The interfaces are split by capability rather than by vendor so that the router can
 * ask "who can do vision right now?" without knowing which products exist. A backend
 * implements only the interfaces it can actually honour.
 */
interface AiProvider {
    /** Stable identifier recorded in execution history, e.g. `device-ai`. */
    val id: String

    val tier: RuntimeTier

    /**
     * Current capabilities. Re-queried rather than cached: an on-device model can
     * become unavailable between two steps of the same run.
     */
    suspend fun capabilities(): ProviderCapabilities

    /** Releases any native resources. Safe to call more than once. */
    fun close() = Unit
}

/** What a provider can do at this moment, as opposed to in principle. */
data class ProviderCapabilities(
    val available: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsStructuredOutput: Boolean = false,
    val supportsSystemPrompt: Boolean = false,
    val maxInputTokens: Int = 0,
    val modelName: String? = null,
    val detail: String = "",
) {
    companion object {
        fun unavailable(detail: String) = ProviderCapabilities(available = false, detail = detail)
    }
}

/** Produces free-form text from a prompt. */
interface ReasoningProvider : AiProvider {
    suspend fun complete(request: TextRequest): InferenceResult<String>
}

/** Answers questions about an image, typically a screenshot. */
interface VisionProvider : AiProvider {
    suspend fun describe(request: VisionRequest): InferenceResult<String>
}

/**
 * Produces a validated domain object.
 *
 * Implementations are free to use a platform structured-output feature when one is
 * available, but must not require it: the default path constrains the prompt, parses
 * the text, and validates the result, which works identically on every tier.
 */
interface StructuredInferenceProvider : AiProvider {
    suspend fun <T : Any> structured(request: StructuredRequest<T>): InferenceResult<T>
}

/** Optional. Used for offline semantic similarity when a provider offers embeddings. */
interface EmbeddingProvider : AiProvider {
    suspend fun embed(texts: List<String>): InferenceResult<List<FloatArray>>
}

data class TextRequest(
    val prompt: String,
    val systemInstruction: String? = null,
    /** Near-zero by default: these are classification calls, not creative writing. */
    val temperature: Float = 0.1f,
    val maxOutputTokens: Int = 512,
    val requirements: InferenceRequirements = InferenceRequirements(),
    val label: String = "",
)

data class VisionRequest(
    val image: Bitmap,
    val prompt: String,
    val systemInstruction: String? = null,
    val temperature: Float = 0.1f,
    val maxOutputTokens: Int = 512,
    val requirements: InferenceRequirements = InferenceRequirements(needsVision = true),
    val label: String = "",
)

data class StructuredRequest<T : Any>(
    val schema: ResponseSchema<T>,
    val prompt: String,
    val systemInstruction: String? = null,
    val image: Bitmap? = null,
    val temperature: Float = 0.0f,
    val maxOutputTokens: Int = 640,
    val requirements: InferenceRequirements = InferenceRequirements(needsStructuredOutput = true),
    val label: String = "",
)
