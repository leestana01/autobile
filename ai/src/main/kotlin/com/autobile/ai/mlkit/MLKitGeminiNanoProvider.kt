package com.autobile.ai.mlkit

import com.autobile.ai.provider.JsonExtractor
import com.autobile.ai.provider.ProviderCapabilities
import com.autobile.ai.provider.ReasoningProvider
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.provider.TextRequest
import com.autobile.ai.provider.VisionProvider
import com.autobile.ai.provider.VisionRequest
import com.autobile.core.common.Logx
import com.autobile.core.model.AiFeatureStatus
import com.autobile.core.model.DeviceAiCapability
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device inference through ML Kit's GenAI Prompt API.
 *
 * The class deliberately owns no assumptions about the device. Presence of the API,
 * model availability, multimodal support and structured-output support are all probed
 * and re-probed, because any of them can differ between two devices running the same
 * Android version, and availability can change between two steps of one run.
 *
 * Every failure is mapped onto [InferenceErrorKind] so the router can decide whether
 * retrying here, waiting, or escalating is the right response.
 */
class MLKitGeminiNanoProvider : ReasoningProvider, VisionProvider, StructuredInferenceProvider {

    override val id: String = PROVIDER_ID
    override val tier: RuntimeTier = RuntimeTier.DEVICE_AI

    private val clientLock = Mutex()
    private var client: GenerativeModel? = null

    @Volatile
    private var cachedCapability: DeviceAiCapability = DeviceAiCapability()

    /** The most recent probe result, without triggering a new one. */
    fun lastKnownCapability(): DeviceAiCapability = cachedCapability

    override suspend fun capabilities(): ProviderCapabilities {
        val capability = probe()
        return ProviderCapabilities(
            available = capability.isUsable,
            supportsVision = capability.multimodalSupported,
            supportsStructuredOutput = capability.structuredOutputSupported,
            supportsSystemPrompt = capability.systemPromptSupported,
            maxInputTokens = capability.tokenLimit,
            modelName = capability.baseModelName,
            detail = capability.lastError ?: capability.featureStatus.name,
        )
    }

    /**
     * Queries the platform for the current on-device model state.
     *
     * Failures are recorded rather than thrown: a device without the backing service
     * is an ordinary, expected configuration, not an error condition.
     */
    suspend fun probe(): DeviceAiCapability = withContext(Dispatchers.IO) {
        val capability = try {
            val model = obtainClient()
            when (val status = model.checkStatus()) {
                FeatureStatus.AVAILABLE -> DeviceAiCapability(
                    promptApiPresent = true,
                    featureStatus = AiFeatureStatus.AVAILABLE,
                    baseModelName = runCatching { model.getBaseModelName() }.getOrNull(),
                    multimodalSupported = true,
                    structuredOutputSupported = runCatching { model.isStructuredOutputFeatureAvailable() }
                        .getOrDefault(false),
                    systemPromptSupported = runCatching { model.isSystemPromptAvailable() }
                        .getOrDefault(false),
                    tokenLimit = runCatching { model.getTokenLimit() }.getOrDefault(0),
                )

                FeatureStatus.DOWNLOADABLE -> DeviceAiCapability(
                    promptApiPresent = true,
                    featureStatus = AiFeatureStatus.DOWNLOADABLE,
                )

                FeatureStatus.DOWNLOADING -> DeviceAiCapability(
                    promptApiPresent = true,
                    featureStatus = AiFeatureStatus.DOWNLOADING,
                )

                FeatureStatus.UNAVAILABLE -> DeviceAiCapability(
                    promptApiPresent = true,
                    featureStatus = AiFeatureStatus.UNAVAILABLE,
                )

                else -> DeviceAiCapability(
                    promptApiPresent = true,
                    featureStatus = AiFeatureStatus.UNKNOWN,
                    lastError = "Unrecognised feature status $status",
                )
            }
        } catch (e: GenAiException) {
            DeviceAiCapability(
                promptApiPresent = true,
                featureStatus = if (e.errorCode == GenAiException.ErrorCode.BUSY) {
                    AiFeatureStatus.BUSY
                } else {
                    AiFeatureStatus.UNAVAILABLE
                },
                quotaExhausted = e.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
                lastError = e.message,
            )
        } catch (e: Throwable) {
            // A device without the backing service throws at class-resolution time.
            DeviceAiCapability(
                promptApiPresent = e !is LinkageError,
                featureStatus = AiFeatureStatus.UNAVAILABLE,
                lastError = e.message ?: e::class.java.simpleName,
            )
        }
        cachedCapability = capability
        capability
    }

    /**
     * Downloads the on-device model, emitting progress in bytes.
     *
     * Only started after the user agrees: the download is large and may be metered.
     */
    fun download(): Flow<ModelDownloadProgress> = flow {
        val model = obtainClient()
        emit(ModelDownloadProgress(state = DownloadState.STARTED))
        model.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadStarted ->
                    emit(ModelDownloadProgress(DownloadState.STARTED, totalBytes = status.bytesToDownload))

                is DownloadStatus.DownloadProgress ->
                    emit(ModelDownloadProgress(DownloadState.IN_PROGRESS, downloadedBytes = status.totalBytesDownloaded))

                is DownloadStatus.DownloadCompleted -> {
                    probe()
                    emit(ModelDownloadProgress(DownloadState.COMPLETED))
                }

                is DownloadStatus.DownloadFailed ->
                    emit(ModelDownloadProgress(DownloadState.FAILED, error = status.e.message))
            }
        }
    }.catch { e ->
        emit(ModelDownloadProgress(DownloadState.FAILED, error = e.message))
    }.flowOn(Dispatchers.IO)

    override suspend fun complete(request: TextRequest): InferenceResult<String> =
        runInference(request.label) { model ->
            val builder = if (request.systemInstruction != null && cachedCapability.systemPromptSupported) {
                GenerateContentRequest.Builder(
                    SystemInstruction(request.systemInstruction),
                    TextPart(request.prompt),
                )
            } else {
                GenerateContentRequest.Builder(TextPart(mergePrompt(request.systemInstruction, request.prompt)))
            }
            builder.temperature = request.temperature
            builder.maxOutputTokens = request.maxOutputTokens
            builder.candidateCount = 1
            model.generateContent(builder.build()).firstText()
        }

    override suspend fun describe(request: VisionRequest): InferenceResult<String> {
        if (!cachedCapability.multimodalSupported) probe()
        if (!cachedCapability.multimodalSupported) {
            return failure(InferenceErrorKind.UNSUPPORTED, "Multimodal prompting unavailable on this device")
        }
        return runInference(request.label) { model ->
            val builder = if (request.systemInstruction != null && cachedCapability.systemPromptSupported) {
                GenerateContentRequest.Builder(
                    SystemInstruction(request.systemInstruction),
                    ImagePart(request.image),
                    TextPart(request.prompt),
                )
            } else {
                GenerateContentRequest.Builder(
                    ImagePart(request.image),
                    TextPart(mergePrompt(request.systemInstruction, request.prompt)),
                )
            }
            builder.temperature = request.temperature
            builder.maxOutputTokens = request.maxOutputTokens
            builder.candidateCount = 1
            model.generateContent(builder.build()).firstText()
        }
    }

    /**
     * Structured output via a constrained prompt plus local parsing.
     *
     * The platform's typed-output feature is still evolving and is unavailable on many
     * supported devices, so the same prompt-and-parse path is used everywhere. That
     * keeps one code path under test instead of two, and it behaves identically
     * whichever tier ends up serving the request.
     */
    override suspend fun <T : Any> structured(request: StructuredRequest<T>): InferenceResult<T> {
        val prompt = buildString {
            append(request.prompt.trimEnd())
            append("\n\n")
            append(request.schema.promptContract())
        }
        val textResult = if (request.image != null) {
            describe(
                VisionRequest(
                    image = request.image,
                    prompt = prompt,
                    systemInstruction = request.systemInstruction,
                    temperature = request.temperature,
                    maxOutputTokens = request.maxOutputTokens,
                    requirements = request.requirements,
                    label = request.label,
                ),
            )
        } else {
            complete(
                TextRequest(
                    prompt = prompt,
                    systemInstruction = request.systemInstruction,
                    temperature = request.temperature,
                    maxOutputTokens = request.maxOutputTokens,
                    requirements = request.requirements,
                    label = request.label,
                ),
            )
        }
        return com.autobile.ai.provider.decodeStructured(textResult, request.schema, tier, id)
    }

    private suspend fun <T : Any> runInference(
        label: String,
        block: suspend (GenerativeModel) -> T?,
    ): InferenceResult<T> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val model = obtainClient()
            val value = block(model)
            InferenceResult(
                value = value,
                confidence = if (value == null) 0f else 1f,
                tier = tier,
                providerId = id,
                rawText = value as? String,
                latencyMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: GenAiException) {
            Logx.w("On-device inference failed [$label] code=${e.errorCode}")
            if (e.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED) {
                cachedCapability = cachedCapability.copy(quotaExhausted = true)
            }
            InferenceResult(
                value = null,
                confidence = 0f,
                tier = tier,
                providerId = id,
                error = InferenceError(
                    kind = mapErrorCode(e.errorCode),
                    message = e.message ?: "on-device inference failed",
                    retryAfterMs = runCatching { e.retryDelay.toMillis() }.getOrNull(),
                ),
                latencyMs = System.currentTimeMillis() - startedAt,
            )
        } catch (e: Throwable) {
            Logx.w("On-device inference unavailable [$label]", e)
            cachedCapability = cachedCapability.copy(
                featureStatus = AiFeatureStatus.UNAVAILABLE,
                lastError = e.message,
            )
            failure(InferenceErrorKind.UNAVAILABLE, e.message ?: "on-device inference unavailable")
        }
    }

    private suspend fun obtainClient(): GenerativeModel = clientLock.withLock {
        client ?: Generation.getClient().also { client = it }
    }

    override fun close() {
        runCatching { client?.close() }
        client = null
    }

    private fun mergePrompt(systemInstruction: String?, prompt: String): String =
        if (systemInstruction.isNullOrBlank()) prompt else "$systemInstruction\n\n$prompt"

    private fun <T : Any> failure(kind: InferenceErrorKind, message: String): InferenceResult<T> =
        InferenceResult(
            value = null,
            confidence = 0f,
            tier = tier,
            providerId = id,
            error = InferenceError(kind, message),
        )

    private fun com.google.mlkit.genai.prompt.GenerateContentResponse.firstText(): String? =
        candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }

    private fun mapErrorCode(code: Int): InferenceErrorKind = when (code) {
        GenAiException.ErrorCode.BUSY -> InferenceErrorKind.BUSY
        GenAiException.ErrorCode.NOT_AVAILABLE,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE,
        -> InferenceErrorKind.UNAVAILABLE

        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> InferenceErrorKind.QUOTA_EXCEEDED
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> InferenceErrorKind.POLICY_BLOCKED
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> InferenceErrorKind.REQUEST_TOO_LARGE
        GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.INVALID_INPUT_IMAGE,
        -> InferenceErrorKind.UNSUPPORTED

        GenAiException.ErrorCode.STRUCTURED_OUTPUT_REQUEST_ERROR,
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR,
        GenAiException.ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR,
        GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
        -> InferenceErrorKind.PARSE_FAILED

        GenAiException.ErrorCode.CANCELLED -> InferenceErrorKind.CANCELLED
        else -> InferenceErrorKind.UNKNOWN
    }

    companion object {
        const val PROVIDER_ID = "device-ai"
    }
}

data class ModelDownloadProgress(
    val state: DownloadState,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

enum class DownloadState { STARTED, IN_PROGRESS, COMPLETED, FAILED }
