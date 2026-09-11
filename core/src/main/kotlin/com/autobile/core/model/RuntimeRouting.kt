package com.autobile.core.model

import kotlinx.serialization.Serializable

/**
 * The execution runtimes available to satisfy a decision, ordered cheapest first.
 *
 * "Cheapest" covers latency, battery and privacy together, which is why deterministic
 * execution outranks on-device inference and on-device inference outranks the cloud.
 * The router's contract is to choose the smallest tier that can actually satisfy the
 * request's requirements — not simply the first tier in the list.
 */
enum class RuntimeTier {
    DETERMINISTIC,
    DEVICE_AI,
    LOCAL_LLM,
    CLOUD_LIGHT,
    CLOUD_ADVANCED;

    val isCloud: Boolean get() = this == CLOUD_LIGHT || this == CLOUD_ADVANCED
    val isLocal: Boolean get() = !isCloud

    val displayName: String
        get() = when (this) {
            DETERMINISTIC -> "Deterministic"
            DEVICE_AI -> "On-device AI"
            LOCAL_LLM -> "Local model"
            CLOUD_LIGHT -> "Cloud (light)"
            CLOUD_ADVANCED -> "Cloud (advanced)"
        }
}

/**
 * Why the router moved a decision up to a more expensive tier.
 *
 * Every escalation records one of these. Escalation is never implicit: without a
 * stated reason it is impossible to tell a necessary cloud call from a wasteful one,
 * and reducing the wasteful ones is a standing goal.
 */
enum class EscalationReason {
    RUNTIME_UNAVAILABLE,
    CONFIDENCE_BELOW_THRESHOLD,
    MODALITY_UNSUPPORTED,
    CONTEXT_COMPLEXITY_EXCEEDED,
    QUOTA_OR_BUSY,
    VALIDATION_FAILED,
    RECOVERY_REQUIRES_STRONGER_REASONING,
    STRUCTURED_OUTPUT_UNSUPPORTED,
    POLICY_REQUIRED;

    val displayName: String
        get() = when (this) {
            RUNTIME_UNAVAILABLE -> "runtime unavailable"
            CONFIDENCE_BELOW_THRESHOLD -> "confidence below threshold"
            MODALITY_UNSUPPORTED -> "required modality unsupported"
            CONTEXT_COMPLEXITY_EXCEEDED -> "context complexity exceeds profile"
            QUOTA_OR_BUSY -> "quota exceeded or model busy"
            VALIDATION_FAILED -> "validation failed"
            RECOVERY_REQUIRES_STRONGER_REASONING -> "recovery requires stronger reasoning"
            STRUCTURED_OUTPUT_UNSUPPORTED -> "structured output unsupported"
            POLICY_REQUIRED -> "required by policy"
        }
}

@Serializable
data class RuntimeSelection(
    val tier: RuntimeTier,
    val providerId: String,
    val escalatedFrom: RuntimeTier? = null,
    val reason: EscalationReason? = null,
) {
    val isEscalation: Boolean get() = escalatedFrom != null
}

/**
 * What a single inference request actually needs in order to succeed.
 *
 * Autobile never hands a whole agent loop to one model. Work is decomposed into small,
 * well-scoped requests, and each one declares its own requirements here so the router
 * can match them against the device's live capabilities instead of guessing.
 */
@Serializable
data class InferenceRequirements(
    val needsVision: Boolean = false,
    val needsStructuredOutput: Boolean = false,
    val needsLongContext: Boolean = false,
    val minConfidence: Float = 0.6f,
    val complexity: TaskComplexity = TaskComplexity.SIMPLE,
    /** Set for inputs that must not leave the device, regardless of tier availability. */
    val localOnly: Boolean = false,
    val estimatedInputTokens: Int = 0,
)

enum class TaskComplexity { SIMPLE, MODERATE, COMPLEX }

/** Uniform result of any inference call, regardless of which tier served it. */
data class InferenceResult<out T>(
    val value: T?,
    val confidence: Float,
    val tier: RuntimeTier,
    val providerId: String,
    val rawText: String? = null,
    val error: InferenceError? = null,
    val latencyMs: Long = 0L,
) {
    val isSuccess: Boolean get() = value != null && error == null

    fun meets(threshold: Float): Boolean = isSuccess && confidence >= threshold
}

data class InferenceError(
    val kind: InferenceErrorKind,
    val message: String,
    val retryAfterMs: Long? = null,
) {
    /** Whether retrying at a higher tier could plausibly succeed where this attempt failed. */
    val escalatable: Boolean
        get() = kind != InferenceErrorKind.CANCELLED && kind != InferenceErrorKind.POLICY_BLOCKED
}

enum class InferenceErrorKind {
    UNAVAILABLE,
    BUSY,
    QUOTA_EXCEEDED,
    REQUEST_TOO_LARGE,
    UNSUPPORTED,
    PARSE_FAILED,
    LOW_CONFIDENCE,
    NETWORK,
    TIMEOUT,
    CANCELLED,
    POLICY_BLOCKED,
    UNKNOWN;

    fun toEscalationReason(): EscalationReason = when (this) {
        UNAVAILABLE, NETWORK, TIMEOUT, UNKNOWN -> EscalationReason.RUNTIME_UNAVAILABLE
        BUSY, QUOTA_EXCEEDED -> EscalationReason.QUOTA_OR_BUSY
        REQUEST_TOO_LARGE -> EscalationReason.CONTEXT_COMPLEXITY_EXCEEDED
        UNSUPPORTED -> EscalationReason.MODALITY_UNSUPPORTED
        PARSE_FAILED -> EscalationReason.STRUCTURED_OUTPUT_UNSUPPORTED
        LOW_CONFIDENCE -> EscalationReason.CONFIDENCE_BELOW_THRESHOLD
        CANCELLED, POLICY_BLOCKED -> EscalationReason.POLICY_REQUIRED
    }
}
