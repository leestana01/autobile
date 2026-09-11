package com.autobile.ai.local

import com.autobile.ai.provider.ProviderCapabilities
import com.autobile.ai.provider.ReasoningProvider
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.provider.TextRequest
import com.autobile.ai.provider.decodeStructured
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.LocalModelCapability
import com.autobile.core.model.RuntimeTier
import java.io.File

/**
 * Inference from a small language model bundled into the app's own storage.
 *
 * This tier exists for devices that have no platform on-device AI but enough compute
 * to run a model directly, and for users who decline cloud access entirely. It is
 * strictly optional: the runtime treats it as unavailable unless a model file has
 * actually been installed, which is the state on a fresh install.
 *
 * [engine] is injected so the model format can change without touching the routing
 * layer above it.
 */
class LocalModelProvider(
    private val modelDirectory: File,
    private val engine: LocalInferenceEngine? = null,
    private val enabled: () -> Boolean = { false },
) : ReasoningProvider, StructuredInferenceProvider {

    override val id: String = PROVIDER_ID
    override val tier: RuntimeTier = RuntimeTier.LOCAL_LLM

    override suspend fun capabilities(): ProviderCapabilities {
        val capability = describeInstallation()
        if (!capability.isUsable || engine == null) {
            return ProviderCapabilities.unavailable(
                when {
                    !enabled() -> "Local model is turned off"
                    engine == null -> "No local inference engine is installed"
                    else -> "No local model file is present"
                },
            )
        }
        return ProviderCapabilities(
            available = engine.isReady(),
            supportsVision = false,
            supportsStructuredOutput = true,
            supportsSystemPrompt = true,
            maxInputTokens = engine.contextWindow(),
            modelName = capability.modelName,
        )
    }

    /** Describes what is installed on disk, independently of whether it can run. */
    fun describeInstallation(): LocalModelCapability {
        if (!enabled()) return LocalModelCapability(supported = false)
        val model = modelDirectory.takeIf { it.isDirectory }
            ?.listFiles { file -> file.isFile && file.length() > 0 }
            ?.maxByOrNull { it.length() }
            ?: return LocalModelCapability(supported = true, modelInstalled = false)
        return LocalModelCapability(
            supported = true,
            modelInstalled = true,
            modelName = model.name,
            modelSizeBytes = model.length(),
        )
    }

    override suspend fun complete(request: TextRequest): InferenceResult<String> {
        val engine = engine?.takeIf { enabled() && it.isReady() }
            ?: return failure("Local model is not available")
        return runCatching {
            val text = engine.generate(
                systemInstruction = request.systemInstruction,
                prompt = request.prompt,
                temperature = request.temperature,
                maxOutputTokens = request.maxOutputTokens,
            )
            InferenceResult(
                value = text,
                confidence = if (text == null) 0f else 1f,
                tier = tier,
                providerId = id,
                rawText = text,
            )
        }.getOrElse { failure(it.message ?: "local inference failed") }
    }

    override suspend fun <T : Any> structured(request: StructuredRequest<T>): InferenceResult<T> {
        if (request.image != null) {
            return InferenceResult(
                value = null,
                confidence = 0f,
                tier = tier,
                providerId = id,
                error = InferenceError(InferenceErrorKind.UNSUPPORTED, "Local model cannot read images"),
            )
        }
        val text = complete(
            TextRequest(
                prompt = request.prompt.trimEnd() + "\n\n" + request.schema.promptContract(),
                systemInstruction = request.systemInstruction,
                temperature = request.temperature,
                maxOutputTokens = request.maxOutputTokens,
                requirements = request.requirements,
                label = request.label,
            ),
        )
        return decodeStructured(text, request.schema, tier, id)
    }

    override fun close() {
        engine?.release()
    }

    private fun <T : Any> failure(message: String): InferenceResult<T> = InferenceResult(
        value = null,
        confidence = 0f,
        tier = tier,
        providerId = id,
        error = InferenceError(InferenceErrorKind.UNAVAILABLE, message),
    )

    companion object {
        const val PROVIDER_ID = "local-model"
    }
}

/**
 * The seam between Autobile and whichever on-device runtime executes a bundled model.
 *
 * Kept minimal on purpose: text in, text out. Anything richer would leak the runtime's
 * shape into the routing layer and make the model format hard to change later.
 */
interface LocalInferenceEngine {
    fun isReady(): Boolean

    fun contextWindow(): Int

    suspend fun generate(
        systemInstruction: String?,
        prompt: String,
        temperature: Float,
        maxOutputTokens: Int,
    ): String?

    fun release() = Unit
}
