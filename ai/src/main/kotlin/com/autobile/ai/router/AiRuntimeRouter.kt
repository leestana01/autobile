package com.autobile.ai.router

import android.graphics.Bitmap
import com.autobile.ai.provider.AiProvider
import com.autobile.ai.provider.ReasoningProvider
import com.autobile.ai.provider.ResponseSchema
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.provider.TextRequest
import com.autobile.ai.provider.VisionProvider
import com.autobile.ai.provider.VisionRequest
import com.autobile.core.common.Logx
import com.autobile.core.model.EscalationReason
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import kotlinx.coroutines.delay
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chooses which runtime answers a question, and escalates only when it has to.
 *
 * The routing rule is "cheapest sufficient tier", where cost means latency, battery
 * and privacy together rather than money alone. A request declares what it needs; the
 * router walks the tiers in order, skips any that cannot meet those needs, and stops
 * at the first one that produces an answer confident enough to act on.
 *
 * Escalation is never silent. Every move to a higher tier records why it happened, so
 * unnecessary cloud calls are visible in history and can be designed out rather than
 * quietly tolerated.
 */
class AiRuntimeRouter(
    private val providers: List<AiProvider>,
    listener: RoutingListener = RoutingListener.NoOp,
) {

    private val listeners = CopyOnWriteArrayList<RoutingListener>().apply { add(listener) }

    /** Registers an observer for the lifetime of one caller, such as a task run. */
    fun addListener(listener: RoutingListener): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    /** Ordered cheapest-first; the order of [RuntimeTier] is the routing order. */
    private val orderedTiers = listOf(
        RuntimeTier.DEVICE_AI,
        RuntimeTier.LOCAL_LLM,
        RuntimeTier.CLOUD_LIGHT,
        RuntimeTier.CLOUD_ADVANCED,
    )

    /**
     * Runs a structured request, escalating until one tier succeeds.
     *
     * @param requirements what the request needs in order to be answerable at all.
     * @param image optional screenshot; requires a tier with vision support.
     */
    suspend fun <T : Any> infer(
        label: String,
        schema: ResponseSchema<T>,
        prompt: String,
        systemInstruction: String? = null,
        image: Bitmap? = null,
        requirements: InferenceRequirements = InferenceRequirements(),
        maxOutputTokens: Int = 640,
    ): RoutedResult<T> {
        val attempts = mutableListOf<RoutingAttempt>()
        var previousTier: RuntimeTier? = null
        var lastError: InferenceError? = null

        for (tier in candidateTiers(requirements)) {
            val provider = providerFor(tier) as? StructuredInferenceProvider ?: continue
            val capabilities = provider.capabilities()

            val skip = skipReason(tier, capabilities, requirements, image != null)
            if (skip != null) {
                attempts += RoutingAttempt(tier, provider.id, skipped = true, reason = skip)
                continue
            }

            listeners.forEach { it.onTierSelected(label, tier, previousTier, lastError?.kind?.toEscalationReason()) }

            val result = provider.structured(
                StructuredRequest(
                    schema = schema,
                    prompt = prompt,
                    systemInstruction = systemInstruction,
                    image = image,
                    requirements = requirements,
                    maxOutputTokens = maxOutputTokens,
                    label = label,
                ),
            )

            // A busy on-device model is usually busy for milliseconds, not seconds.
            // One short retry here is far cheaper than escalating to the network.
            val settled = if (result.error?.kind == InferenceErrorKind.BUSY && tier.isLocal) {
                delay(result.error?.retryAfterMs?.coerceAtMost(MAX_BUSY_RETRY_MS) ?: BUSY_RETRY_MS)
                provider.structured(
                    StructuredRequest(
                        schema = schema,
                        prompt = prompt,
                        systemInstruction = systemInstruction,
                        image = image,
                        requirements = requirements,
                        maxOutputTokens = maxOutputTokens,
                        label = label,
                    ),
                )
            } else {
                result
            }

            attempts += RoutingAttempt(
                tier = tier,
                providerId = provider.id,
                skipped = false,
                confidence = settled.confidence,
                errorKind = settled.error?.kind,
                latencyMs = settled.latencyMs,
            )

            if (settled.meets(requirements.minConfidence)) {
                listeners.forEach { it.onResolved(label, tier, settled.confidence, escalated = previousTier != null) }
                return RoutedResult(settled, attempts)
            }

            lastError = settled.error ?: InferenceError(
                InferenceErrorKind.LOW_CONFIDENCE,
                "confidence ${"%.2f".format(settled.confidence)} below ${requirements.minConfidence}",
            )
            if (!lastError.escalatable) break
            previousTier = tier
        }

        Logx.d("No runtime satisfied [$label]; attempts=${attempts.size}")
        listeners.forEach { it.onExhausted(label, attempts) }
        return RoutedResult(
            InferenceResult(
                value = null,
                confidence = 0f,
                tier = previousTier ?: RuntimeTier.DETERMINISTIC,
                providerId = "none",
                error = lastError ?: InferenceError(
                    InferenceErrorKind.UNAVAILABLE,
                    "No runtime could answer this request",
                ),
            ),
            attempts,
        )
    }

    /** Free-form completion. Used where the caller parses the answer itself. */
    suspend fun completeText(
        label: String,
        prompt: String,
        systemInstruction: String? = null,
        requirements: InferenceRequirements = InferenceRequirements(),
        maxOutputTokens: Int = 512,
    ): RoutedResult<String> {
        val attempts = mutableListOf<RoutingAttempt>()
        var previousTier: RuntimeTier? = null
        var lastError: InferenceError? = null

        for (tier in candidateTiers(requirements)) {
            val provider = providerFor(tier) as? ReasoningProvider ?: continue
            val capabilities = provider.capabilities()
            val skip = skipReason(tier, capabilities, requirements, false)
            if (skip != null) {
                attempts += RoutingAttempt(tier, provider.id, skipped = true, reason = skip)
                continue
            }

            listeners.forEach { it.onTierSelected(label, tier, previousTier, lastError?.kind?.toEscalationReason()) }
            val result = provider.complete(
                TextRequest(
                    prompt = prompt,
                    systemInstruction = systemInstruction,
                    requirements = requirements,
                    maxOutputTokens = maxOutputTokens,
                    label = label,
                ),
            )
            attempts += RoutingAttempt(tier, provider.id, false, result.confidence, result.error?.kind, result.latencyMs)
            if (result.isSuccess) {
                listeners.forEach { it.onResolved(label, tier, result.confidence, escalated = previousTier != null) }
                return RoutedResult(result, attempts)
            }
            lastError = result.error
            if (lastError?.escalatable == false) break
            previousTier = tier
        }

        listeners.forEach { it.onExhausted(label, attempts) }
        return RoutedResult(
            InferenceResult(
                value = null,
                confidence = 0f,
                tier = previousTier ?: RuntimeTier.DETERMINISTIC,
                providerId = "none",
                error = lastError ?: InferenceError(InferenceErrorKind.UNAVAILABLE, "No runtime available"),
            ),
            attempts,
        )
    }

    /** Describes a screenshot. Only tiers with vision support are considered. */
    suspend fun describeImage(
        label: String,
        image: Bitmap,
        prompt: String,
        systemInstruction: String? = null,
        requirements: InferenceRequirements = InferenceRequirements(needsVision = true),
    ): RoutedResult<String> {
        val attempts = mutableListOf<RoutingAttempt>()
        var lastError: InferenceError? = null
        var previousTier: RuntimeTier? = null

        for (tier in candidateTiers(requirements.copy(needsVision = true))) {
            val provider = providerFor(tier) as? VisionProvider ?: continue
            val capabilities = provider.capabilities()
            val skip = skipReason(tier, capabilities, requirements, true)
            if (skip != null) {
                attempts += RoutingAttempt(tier, provider.id, skipped = true, reason = skip)
                continue
            }
            listeners.forEach { it.onTierSelected(label, tier, previousTier, lastError?.kind?.toEscalationReason()) }
            val result = provider.describe(
                VisionRequest(image = image, prompt = prompt, systemInstruction = systemInstruction, label = label),
            )
            attempts += RoutingAttempt(tier, provider.id, false, result.confidence, result.error?.kind, result.latencyMs)
            if (result.isSuccess) {
                listeners.forEach { it.onResolved(label, tier, result.confidence, escalated = previousTier != null) }
                return RoutedResult(result, attempts)
            }
            lastError = result.error
            if (lastError?.escalatable == false) break
            previousTier = tier
        }

        listeners.forEach { it.onExhausted(label, attempts) }
        return RoutedResult(
            InferenceResult(
                value = null,
                confidence = 0f,
                tier = previousTier ?: RuntimeTier.DETERMINISTIC,
                providerId = "none",
                error = lastError ?: InferenceError(InferenceErrorKind.UNAVAILABLE, "No vision runtime available"),
            ),
            attempts,
        )
    }

    /** True when at least one tier could currently answer a request of this shape. */
    suspend fun hasRuntimeFor(requirements: InferenceRequirements): Boolean =
        candidateTiers(requirements).any { tier ->
            val provider = providerFor(tier) ?: return@any false
            skipReason(tier, provider.capabilities(), requirements, requirements.needsVision) == null
        }

    private fun candidateTiers(requirements: InferenceRequirements): List<RuntimeTier> =
        if (requirements.localOnly) orderedTiers.filter { it.isLocal } else orderedTiers

    private fun providerFor(tier: RuntimeTier): AiProvider? = providers.firstOrNull { it.tier == tier }

    /**
     * Returns why [tier] cannot serve this request, or null when it can.
     *
     * Checking before calling matters: a tier that is going to reject the request
     * anyway should not be given the chance to spend time, battery or an API quota
     * discovering that.
     */
    private fun skipReason(
        tier: RuntimeTier,
        capabilities: com.autobile.ai.provider.ProviderCapabilities,
        requirements: InferenceRequirements,
        hasImage: Boolean,
    ): EscalationReason? = when {
        !capabilities.available -> EscalationReason.RUNTIME_UNAVAILABLE
        (hasImage || requirements.needsVision) && !capabilities.supportsVision ->
            EscalationReason.MODALITY_UNSUPPORTED

        requirements.localOnly && tier.isCloud -> EscalationReason.POLICY_REQUIRED
        requirements.needsLongContext && capabilities.maxInputTokens in 1 until LONG_CONTEXT_TOKENS ->
            EscalationReason.CONTEXT_COMPLEXITY_EXCEEDED

        requirements.estimatedInputTokens > 0 &&
            capabilities.maxInputTokens > 0 &&
            requirements.estimatedInputTokens > capabilities.maxInputTokens ->
            EscalationReason.CONTEXT_COMPLEXITY_EXCEEDED

        else -> null
    }

    fun close() = providers.forEach { runCatching { it.close() } }

    private companion object {
        const val BUSY_RETRY_MS = 250L
        const val MAX_BUSY_RETRY_MS = 1_500L
        const val LONG_CONTEXT_TOKENS = 4_000
    }
}

/** An inference result plus the record of how the router arrived at it. */
data class RoutedResult<T : Any>(
    val result: InferenceResult<T>,
    val attempts: List<RoutingAttempt>,
) {
    val value: T? get() = result.value
    val isSuccess: Boolean get() = result.isSuccess
    val tier: RuntimeTier get() = result.tier
    val confidence: Float get() = result.confidence

    /** True when the answer came from a tier that did not require the network. */
    val resolvedLocally: Boolean get() = isSuccess && result.tier.isLocal

    val usedCloud: Boolean get() = attempts.any { !it.skipped && it.tier.isCloud }

    /**
     * True when escalating to the cloud is what produced the answer, as opposed to the
     * cloud merely having been tried. Distinguishing the two is what makes it possible
     * to tell a justified escalation from a wasted one.
     */
    val cloudWasDecisive: Boolean get() = isSuccess && result.tier.isCloud
}

data class RoutingAttempt(
    val tier: RuntimeTier,
    val providerId: String,
    val skipped: Boolean,
    val confidence: Float = 0f,
    val errorKind: InferenceErrorKind? = null,
    val latencyMs: Long = 0,
    val reason: EscalationReason? = null,
)

/** Hook for recording routing decisions into execution history and metrics. */
interface RoutingListener {
    fun onTierSelected(label: String, tier: RuntimeTier, escalatedFrom: RuntimeTier?, reason: EscalationReason?)
    fun onResolved(label: String, tier: RuntimeTier, confidence: Float, escalated: Boolean)
    fun onExhausted(label: String, attempts: List<RoutingAttempt>)

    object NoOp : RoutingListener {
        override fun onTierSelected(
            label: String,
            tier: RuntimeTier,
            escalatedFrom: RuntimeTier?,
            reason: EscalationReason?,
        ) = Unit

        override fun onResolved(label: String, tier: RuntimeTier, confidence: Float, escalated: Boolean) = Unit
        override fun onExhausted(label: String, attempts: List<RoutingAttempt>) = Unit
    }
}
