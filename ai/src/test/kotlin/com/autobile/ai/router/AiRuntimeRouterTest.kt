package com.autobile.ai.router

import com.autobile.ai.provider.ProviderCapabilities
import com.autobile.ai.provider.ResponseSchema
import com.autobile.ai.provider.StructuredInferenceProvider
import com.autobile.ai.provider.StructuredRequest
import com.autobile.ai.provider.stringOr
import com.autobile.core.model.EscalationReason
import com.autobile.core.model.InferenceError
import com.autobile.core.model.InferenceErrorKind
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.InferenceResult
import com.autobile.core.model.RuntimeTier
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Routing decides how much of a user's screen leaves their device and how much battery
 * a run costs, so the escalation rules are asserted directly rather than inferred from
 * end-to-end behaviour.
 */
class AiRuntimeRouterTest {

    private data class Answer(val text: String)

    private val schema = ResponseSchema(
        name = "Answer",
        fieldGuide = "text: string",
        example = """{"text":"ok"}""",
        parser = { json -> Answer(json.stringOr("text")) },
    )

    private class FakeProvider(
        override val tier: RuntimeTier,
        override val id: String = tier.name.lowercase(),
        private val capabilities: ProviderCapabilities = ProviderCapabilities(
            available = true,
            supportsVision = true,
            supportsStructuredOutput = true,
            maxInputTokens = 4_000,
        ),
        private val response: (StructuredRequest<*>) -> InferenceResult<*>,
    ) : StructuredInferenceProvider {
        var callCount: Int = 0
            private set

        override suspend fun capabilities(): ProviderCapabilities = capabilities

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> structured(request: StructuredRequest<T>): InferenceResult<T> {
            callCount++
            return response(request) as InferenceResult<T>
        }
    }

    private fun success(tier: RuntimeTier, confidence: Float = 0.9f) =
        InferenceResult(Answer("ok"), confidence, tier, tier.name.lowercase())

    private fun failure(tier: RuntimeTier, kind: InferenceErrorKind) =
        InferenceResult<Answer>(null, 0f, tier, tier.name.lowercase(), error = InferenceError(kind, kind.name))

    private class RecordingListener : RoutingListener {
        val escalations = mutableListOf<Pair<RuntimeTier, EscalationReason?>>()
        var exhausted = false

        override fun onTierSelected(
            label: String,
            tier: RuntimeTier,
            escalatedFrom: RuntimeTier?,
            reason: EscalationReason?,
        ) {
            if (escalatedFrom != null) escalations += tier to reason
        }

        override fun onResolved(label: String, tier: RuntimeTier, confidence: Float, escalated: Boolean) = Unit
        override fun onExhausted(label: String, attempts: List<RoutingAttempt>) {
            exhausted = true
        }
    }

    @Test
    fun `a confident on-device answer never reaches the cloud`() = runTest {
        val cloud = FakeProvider(RuntimeTier.CLOUD_LIGHT) { success(RuntimeTier.CLOUD_LIGHT) }
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(RuntimeTier.DEVICE_AI) { success(RuntimeTier.DEVICE_AI) },
                cloud,
            ),
        )

        val routed = router.infer("test", schema, "prompt")

        assertThat(routed.isSuccess).isTrue()
        assertThat(routed.tier).isEqualTo(RuntimeTier.DEVICE_AI)
        assertThat(routed.usedCloud).isFalse()
        assertThat(cloud.callCount).isEqualTo(0)
    }

    @Test
    fun `an unavailable device tier escalates to the cloud`() = runTest {
        val listener = RecordingListener()
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(
                    RuntimeTier.DEVICE_AI,
                    capabilities = ProviderCapabilities.unavailable("no model"),
                ) { success(RuntimeTier.DEVICE_AI) },
                FakeProvider(RuntimeTier.CLOUD_LIGHT) { success(RuntimeTier.CLOUD_LIGHT) },
            ),
            listener,
        )

        val routed = router.infer("test", schema, "prompt")

        assertThat(routed.tier).isEqualTo(RuntimeTier.CLOUD_LIGHT)
        assertThat(routed.attempts.first { it.tier == RuntimeTier.DEVICE_AI }.skipped).isTrue()
        assertThat(routed.attempts.first { it.tier == RuntimeTier.DEVICE_AI }.reason)
            .isEqualTo(EscalationReason.RUNTIME_UNAVAILABLE)
    }

    @Test
    fun `a low confidence answer escalates rather than being acted on`() = runTest {
        val listener = RecordingListener()
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(RuntimeTier.DEVICE_AI) { success(RuntimeTier.DEVICE_AI, confidence = 0.2f) },
                FakeProvider(RuntimeTier.CLOUD_LIGHT) { success(RuntimeTier.CLOUD_LIGHT, confidence = 0.95f) },
            ),
            listener,
        )

        val routed = router.infer("test", schema, "prompt", requirements = InferenceRequirements(minConfidence = 0.7f))

        assertThat(routed.tier).isEqualTo(RuntimeTier.CLOUD_LIGHT)
        assertThat(listener.escalations.map { it.second })
            .contains(EscalationReason.CONFIDENCE_BELOW_THRESHOLD)
    }

    @Test
    fun `a local-only request is never sent to any cloud tier`() = runTest {
        val cloud = FakeProvider(RuntimeTier.CLOUD_ADVANCED) { success(RuntimeTier.CLOUD_ADVANCED) }
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(RuntimeTier.DEVICE_AI) { failure(RuntimeTier.DEVICE_AI, InferenceErrorKind.UNAVAILABLE) },
                cloud,
            ),
        )

        val routed = router.infer(
            "test",
            schema,
            "prompt",
            requirements = InferenceRequirements(localOnly = true),
        )

        assertThat(routed.isSuccess).isFalse()
        assertThat(cloud.callCount).isEqualTo(0)
    }

    @Test
    fun `a tier without vision support is skipped for image requests`() = runTest {
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(
                    RuntimeTier.DEVICE_AI,
                    capabilities = ProviderCapabilities(available = true, supportsVision = false),
                ) { success(RuntimeTier.DEVICE_AI) },
                FakeProvider(RuntimeTier.CLOUD_ADVANCED) { success(RuntimeTier.CLOUD_ADVANCED) },
            ),
        )

        val routed = router.infer(
            "test",
            schema,
            "prompt",
            requirements = InferenceRequirements(needsVision = true),
        )

        assertThat(routed.tier).isEqualTo(RuntimeTier.CLOUD_ADVANCED)
        assertThat(routed.attempts.first { it.tier == RuntimeTier.DEVICE_AI }.reason)
            .isEqualTo(EscalationReason.MODALITY_UNSUPPORTED)
    }

    @Test
    fun `a busy on-device model is retried once before escalating`() = runTest {
        var attempt = 0
        val device = FakeProvider(RuntimeTier.DEVICE_AI) {
            attempt++
            if (attempt == 1) failure(RuntimeTier.DEVICE_AI, InferenceErrorKind.BUSY) else success(RuntimeTier.DEVICE_AI)
        }
        val cloud = FakeProvider(RuntimeTier.CLOUD_LIGHT) { success(RuntimeTier.CLOUD_LIGHT) }
        val router = AiRuntimeRouter(listOf(device, cloud))

        val routed = router.infer("test", schema, "prompt")

        assertThat(routed.tier).isEqualTo(RuntimeTier.DEVICE_AI)
        assertThat(device.callCount).isEqualTo(2)
        assertThat(cloud.callCount).isEqualTo(0)
    }

    @Test
    fun `a policy blocked failure stops escalation instead of retrying elsewhere`() = runTest {
        val cloud = FakeProvider(RuntimeTier.CLOUD_LIGHT) { success(RuntimeTier.CLOUD_LIGHT) }
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(RuntimeTier.DEVICE_AI) {
                    failure(RuntimeTier.DEVICE_AI, InferenceErrorKind.POLICY_BLOCKED)
                },
                cloud,
            ),
        )

        val routed = router.infer("test", schema, "prompt")

        assertThat(routed.isSuccess).isFalse()
        assertThat(cloud.callCount).isEqualTo(0)
    }

    @Test
    fun `a context too large for a tier skips it without spending a call`() = runTest {
        val device = FakeProvider(
            RuntimeTier.DEVICE_AI,
            capabilities = ProviderCapabilities(available = true, maxInputTokens = 1_000),
        ) { success(RuntimeTier.DEVICE_AI) }
        val cloud = FakeProvider(
            RuntimeTier.CLOUD_ADVANCED,
            capabilities = ProviderCapabilities(available = true, supportsVision = true, maxInputTokens = 32_000),
        ) { success(RuntimeTier.CLOUD_ADVANCED) }
        val router = AiRuntimeRouter(listOf(device, cloud))

        val routed = router.infer(
            "test",
            schema,
            "prompt",
            requirements = InferenceRequirements(estimatedInputTokens = 20_000),
        )

        assertThat(device.callCount).isEqualTo(0)
        assertThat(routed.tier).isEqualTo(RuntimeTier.CLOUD_ADVANCED)
        assertThat(routed.attempts.first { it.tier == RuntimeTier.DEVICE_AI }.reason)
            .isEqualTo(EscalationReason.CONTEXT_COMPLEXITY_EXCEEDED)
    }

    @Test
    fun `exhausting every tier reports failure rather than a fabricated answer`() = runTest {
        val listener = RecordingListener()
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(RuntimeTier.DEVICE_AI) { failure(RuntimeTier.DEVICE_AI, InferenceErrorKind.UNAVAILABLE) },
                FakeProvider(RuntimeTier.CLOUD_LIGHT) { failure(RuntimeTier.CLOUD_LIGHT, InferenceErrorKind.NETWORK) },
            ),
            listener,
        )

        val routed = router.infer("test", schema, "prompt")

        assertThat(routed.isSuccess).isFalse()
        assertThat(routed.value).isNull()
        assertThat(listener.exhausted).isTrue()
    }

    @Test
    fun `cloud is reported as decisive only when it produced the answer`() = runTest {
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(RuntimeTier.DEVICE_AI) { success(RuntimeTier.DEVICE_AI) },
                FakeProvider(RuntimeTier.CLOUD_LIGHT) { success(RuntimeTier.CLOUD_LIGHT) },
            ),
        )

        val routed = router.infer("test", schema, "prompt")

        assertThat(routed.resolvedLocally).isTrue()
        assertThat(routed.cloudWasDecisive).isFalse()
    }

    @Test
    fun `availability check reports false when no tier can serve the request`() = runTest {
        val router = AiRuntimeRouter(
            listOf(
                FakeProvider(
                    RuntimeTier.DEVICE_AI,
                    capabilities = ProviderCapabilities.unavailable("none"),
                ) { success(RuntimeTier.DEVICE_AI) },
            ),
        )

        assertThat(router.hasRuntimeFor(InferenceRequirements())).isFalse()
    }
}
