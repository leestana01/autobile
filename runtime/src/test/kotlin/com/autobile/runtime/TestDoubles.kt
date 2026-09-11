package com.autobile.runtime

import com.autobile.ai.provider.ProviderCapabilities
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.core.model.Bounds
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.UiNode

/**
 * A provider whose answers are supplied by the test.
 *
 * Keyed by the request label so one instance can serve a flow that asks several
 * different questions, and so a test can assert that a particular question was never
 * asked at all.
 */
class ScriptedProvider(
    override val tier: RuntimeTier = RuntimeTier.DEVICE_AI,
    override val id: String = "scripted",
    private val available: Boolean = true,
    private val answers: MutableMap<String, Any> = mutableMapOf(),
) : StructuredInferenceProvider {

    val requestedLabels = mutableListOf<String>()

    fun answerWith(label: String, value: Any): ScriptedProvider {
        answers[label] = value
        return this
    }

    override suspend fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        available = available,
        supportsVision = true,
        supportsStructuredOutput = true,
        supportsSystemPrompt = true,
        maxInputTokens = 8_000,
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> structured(request: StructuredRequest<T>): InferenceResult<T> {
        requestedLabels += request.label
        val answer = answers[request.label]
            ?: return InferenceResult(
                value = null,
                confidence = 0f,
                tier = tier,
                providerId = id,
                error = InferenceError(InferenceErrorKind.UNAVAILABLE, "no scripted answer for ${request.label}"),
            )
        return InferenceResult(answer as T, confidence = 0.9f, tier = tier, providerId = id)
    }
}

fun routerWith(vararg providers: StructuredInferenceProvider) = AiRuntimeRouter(providers.toList())

fun node(
    id: String,
    text: String? = null,
    resourceId: String? = null,
    clickable: Boolean = true,
    indexPath: List<Int> = emptyList(),
    bounds: Bounds = Bounds(0, 0, 400, 120),
) = UiNode(
    nodeId = id,
    text = text,
    resourceId = resourceId,
    clickable = clickable,
    indexPath = indexPath,
    bounds = bounds,
)

fun screen(
    packageName: String = "com.example.business",
    windowTitle: String = "Reports",
    vararg nodes: UiNode,
) = ScreenSnapshot(
    packageName = packageName,
    windowTitle = windowTitle,
    nodes = nodes.toList(),
    screenWidth = 1080,
    screenHeight = 2400,
)
