package com.autobile.ai.cloud

import android.graphics.Bitmap
import android.util.Base64
import com.autobile.ai.provider.ProviderCapabilities
import com.autobile.ai.provider.ReasoningProvider
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.provider.TextRequest
import com.autobile.ai.provider.VisionProvider
import com.autobile.ai.provider.VisionRequest
import com.autobile.core.common.AutobileJson
import com.autobile.core.common.Logx
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Network inference, used only when no local tier can answer.
 *
 * Speaks the Gemini `generateContent` REST shape. The endpoint, model and key are all
 * user-supplied so that the product is not bound to one vendor and so that a user can
 * point it at a deployment they control.
 *
 * Two tiers share this implementation. The light tier is a small, fast model used for
 * classification; the advanced tier is a larger model used for recovery planning and
 * for reading screenshots. Splitting them keeps the common case cheap.
 */
class CloudAiProvider(
    override val tier: RuntimeTier,
    private val config: () -> CloudConfig,
) : ReasoningProvider, VisionProvider, StructuredInferenceProvider {

    override val id: String = if (tier == RuntimeTier.CLOUD_ADVANCED) "cloud-advanced" else "cloud-light"

    private val consecutiveFailures = AtomicLong(0)

    override suspend fun capabilities(): ProviderCapabilities {
        val cfg = config()
        if (!cfg.isUsable) return ProviderCapabilities.unavailable("Cloud access is off or unconfigured")
        return ProviderCapabilities(
            available = true,
            supportsVision = cfg.allowImages,
            supportsStructuredOutput = true,
            supportsSystemPrompt = true,
            maxInputTokens = cfg.maxInputTokens,
            modelName = cfg.modelFor(tier),
            detail = cfg.endpointLabel,
        )
    }

    override suspend fun complete(request: TextRequest): InferenceResult<String> =
        send(request.label, request.systemInstruction, request.prompt, null, request.temperature, request.maxOutputTokens)

    override suspend fun describe(request: VisionRequest): InferenceResult<String> {
        val cfg = config()
        if (!cfg.allowImages) {
            return failure(
                InferenceErrorKind.POLICY_BLOCKED,
                "Sending screenshots off the device is disabled",
            )
        }
        return send(
            request.label,
            request.systemInstruction,
            request.prompt,
            request.image,
            request.temperature,
            request.maxOutputTokens,
        )
    }

    override suspend fun <T : Any> structured(request: StructuredRequest<T>): InferenceResult<T> {
        val prompt = buildString {
            append(request.prompt.trimEnd())
            append("\n\n")
            append(request.schema.promptContract())
        }
        val text = send(
            request.label,
            request.systemInstruction,
            prompt,
            request.image,
            request.temperature,
            request.maxOutputTokens,
            forceJson = true,
        )
        return com.autobile.ai.provider.decodeStructured(text, request.schema, tier, id)
    }

    private suspend fun send(
        label: String,
        systemInstruction: String?,
        prompt: String,
        image: Bitmap?,
        temperature: Float,
        maxOutputTokens: Int,
        forceJson: Boolean = false,
    ): InferenceResult<String> = withContext(Dispatchers.IO) {
        val cfg = config()
        if (!cfg.isUsable) {
            return@withContext failure<String>(InferenceErrorKind.UNAVAILABLE, "Cloud access is off or unconfigured")
        }
        if (image != null && !cfg.allowImages) {
            return@withContext failure<String>(
                InferenceErrorKind.POLICY_BLOCKED,
                "Sending screenshots off the device is disabled",
            )
        }

        val startedAt = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            val url = URL(cfg.requestUrl(tier))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = cfg.connectTimeoutMs
                readTimeout = cfg.readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (cfg.apiKey.isNotBlank()) setRequestProperty("x-goog-api-key", cfg.apiKey)
            }
            val body = buildRequestBody(systemInstruction, prompt, image, temperature, maxOutputTokens, forceJson)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                consecutiveFailures.incrementAndGet()
                Logx.w("Cloud inference HTTP $status [$label]")
                return@withContext failure<String>(mapHttpStatus(status), "HTTP $status ${Logx.redact(error)}")
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val text = extractText(responseText)
            consecutiveFailures.set(0)
            InferenceResult(
                value = text,
                confidence = if (text == null) 0f else 1f,
                tier = tier,
                providerId = id,
                rawText = text,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: java.net.SocketTimeoutException) {
            consecutiveFailures.incrementAndGet()
            failure(InferenceErrorKind.TIMEOUT, e.message ?: "cloud request timed out")
        } catch (e: java.io.IOException) {
            consecutiveFailures.incrementAndGet()
            failure(InferenceErrorKind.NETWORK, e.message ?: "cloud request failed")
        } catch (e: Throwable) {
            consecutiveFailures.incrementAndGet()
            Logx.w("Cloud inference failed [$label]", e)
            failure(InferenceErrorKind.UNKNOWN, e.message ?: "cloud request failed")
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildRequestBody(
        systemInstruction: String?,
        prompt: String,
        image: Bitmap?,
        temperature: Float,
        maxOutputTokens: Int,
        forceJson: Boolean,
    ): String {
        val payload = buildJsonObject {
            if (!systemInstruction.isNullOrBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemInstruction) })
                    }
                }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            if (image != null) {
                                add(
                                    buildJsonObject {
                                        putJsonObject("inline_data") {
                                            put("mime_type", "image/jpeg")
                                            put("data", encodeImage(image))
                                        }
                                    },
                                )
                            }
                            add(buildJsonObject { put("text", prompt) })
                        }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                put("maxOutputTokens", maxOutputTokens)
                put("candidateCount", 1)
                if (forceJson) put("responseMimeType", "application/json")
            }
        }
        return payload.toString()
    }

    private fun encodeImage(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun extractText(response: String): String? {
        val root = runCatching { AutobileJson.parseToJsonElement(response) as? JsonObject }.getOrNull() ?: return null
        val candidates = root["candidates"] as? JsonArray ?: return null
        val first = candidates.firstOrNull() as? JsonObject ?: return null
        val content = first["content"] as? JsonObject ?: return null
        val parts = content["parts"] as? JsonArray ?: return null
        val text = parts.mapNotNull { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content
        }.joinToString("")
        return text.takeIf { it.isNotBlank() }
    }

    private fun mapHttpStatus(status: Int): InferenceErrorKind = when (status) {
        400 -> InferenceErrorKind.UNSUPPORTED
        401, 403 -> InferenceErrorKind.POLICY_BLOCKED
        413 -> InferenceErrorKind.REQUEST_TOO_LARGE
        429 -> InferenceErrorKind.QUOTA_EXCEEDED
        in 500..599 -> InferenceErrorKind.UNAVAILABLE
        else -> InferenceErrorKind.UNKNOWN
    }

    private fun <T : Any> failure(kind: InferenceErrorKind, message: String): InferenceResult<T> =
        InferenceResult(
            value = null,
            confidence = 0f,
            tier = tier,
            providerId = id,
            error = InferenceError(kind, message),
        )

    private companion object {
        const val JPEG_QUALITY = 80
    }
}

/**
 * Everything needed to reach a cloud endpoint.
 *
 * [allowImages] is separate from [enabled] on purpose: users routinely accept sending
 * a few lines of interface text off the device while refusing to send a picture of
 * their screen, and the two decisions must be independently revocable.
 */
data class CloudConfig(
    val enabled: Boolean = false,
    val endpoint: String = DEFAULT_ENDPOINT,
    val apiKey: String = "",
    val lightModel: String = DEFAULT_LIGHT_MODEL,
    val advancedModel: String = DEFAULT_ADVANCED_MODEL,
    val allowImages: Boolean = false,
    val maxInputTokens: Int = 32_000,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 45_000,
) {
    val isUsable: Boolean get() = enabled && endpoint.isNotBlank() && apiKey.isNotBlank()

    val endpointLabel: String
        get() = runCatching { URL(endpoint).host }.getOrNull() ?: endpoint

    fun modelFor(tier: RuntimeTier): String =
        if (tier == RuntimeTier.CLOUD_ADVANCED) advancedModel else lightModel

    fun requestUrl(tier: RuntimeTier): String =
        "${endpoint.trimEnd('/')}/models/${modelFor(tier)}:generateContent"

    companion object {
        const val DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_LIGHT_MODEL = "gemini-2.5-flash-lite"
        const val DEFAULT_ADVANCED_MODEL = "gemini-2.5-flash"
    }
}
