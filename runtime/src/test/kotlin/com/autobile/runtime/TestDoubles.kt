package com.autobile.runtime

import com.autobile.ai.provider.ProviderCapabilities
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.core.model.Bounds
import com.autobile.core.model.Direction
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.UiNode
import com.autobile.runtime.control.ActionResult
import com.autobile.runtime.control.ScreenActuator
import com.autobile.runtime.perception.ScreenObserver
import com.autobile.runtime.perception.ScreenshotCapture

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

/**
 * An in-memory screen that both observes and acts.
 *
 * Implementing both halves of the device surface in one object keeps a test's setup to
 * a single fixture, and lets an assertion check what was pressed against what was shown
 * at the time.
 */
class FakeScreen(
    private var current: ScreenSnapshot = ScreenSnapshot(),
    private val perception: PerceptionResult? = null,
    /** Swapped in after the first successful action, to model a screen transition. */
    private val nextScreen: ScreenSnapshot? = null,
    private val screenshot: ScreenshotCapture = ScreenshotCapture.Unavailable("not needed"),
) : ScreenObserver, ScreenActuator {

    val clicked = mutableListOf<String>()
    val typed = mutableListOf<Pair<String, String>>()
    val launched = mutableListOf<String>()
    val scrolled = mutableListOf<Direction>()
    var backPresses: Int = 0
        private set

    override suspend fun observe(settleMs: Long): PerceptionResult =
        perception ?: PerceptionResult.Success(current)

    override suspend fun observeStable(timeoutMs: Long, settleMs: Long): PerceptionResult =
        perception ?: PerceptionResult.Success(current)

    override suspend fun captureScreenshot(): ScreenshotCapture = screenshot

    override suspend fun click(node: UiNode): ActionResult {
        clicked += node.nodeId
        advance()
        return ActionResult.Performed("click")
    }

    override suspend fun longPress(node: UiNode, durationMs: Long): ActionResult {
        clicked += node.nodeId
        advance()
        return ActionResult.Performed("long press")
    }

    override suspend fun tapAt(bounds: Bounds): ActionResult = ActionResult.Performed("tap")

    override suspend fun tapRatio(xRatio: Float, yRatio: Float): ActionResult = ActionResult.Performed("tap")

    override suspend fun swipe(direction: Direction, distanceRatio: Float, durationMs: Long): ActionResult {
        scrolled += direction
        return ActionResult.Performed("swipe")
    }

    override suspend fun scroll(container: UiNode?, direction: Direction): ActionResult {
        scrolled += direction
        return ActionResult.Performed("scroll")
    }

    override suspend fun inputText(node: UiNode, value: String, clearExisting: Boolean): ActionResult {
        typed += node.nodeId to value
        advance()
        return ActionResult.Performed("input")
    }

    override fun pressBack(): ActionResult {
        backPresses++
        return ActionResult.Performed("back")
    }

    override fun pressHome(): ActionResult = ActionResult.Performed("home")

    override fun launchApp(packageName: String, activity: String?): ActionResult {
        launched += packageName
        advance()
        return ActionResult.Performed("launch")
    }

    /** Moves to the follow-up screen, once, so a recovery tap can reveal a new target. */
    private fun advance() {
        nextScreen?.let { current = it }
    }
}
