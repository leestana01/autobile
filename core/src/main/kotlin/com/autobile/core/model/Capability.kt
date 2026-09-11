package com.autobile.core.model

import kotlinx.serialization.Serializable

/**
 * What this specific device can actually do, right now.
 *
 * Every field is probed at run time and re-probed when it may have changed; nothing
 * here may be assumed at install time. In particular, OS support and AI runtime
 * support are tracked as independent axes: a device meeting the minimum Android
 * version says nothing about whether on-device generative AI is available on it.
 */
@Serializable
data class DeviceCapabilityProfile(
    val apiLevel: Int = 0,
    val accessibilityConnected: Boolean = false,
    val gestureDispatchSupported: Boolean = false,
    val screenshotSupported: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val deviceAi: DeviceAiCapability = DeviceAiCapability(),
    val localModel: LocalModelCapability = LocalModelCapability(),
    val cloud: CloudCapability = CloudCapability(),
    val network: NetworkState = NetworkState.UNKNOWN,
    val computeClass: ComputeClass = ComputeClass.UNKNOWN,
    val restrictions: List<RuntimeRestriction> = emptyList(),
    val evaluatedAt: Long = 0L,
) {
    /** The execution strategy this device qualifies for, recomputed from the fields above. */
    val runtimeProfile: DeviceRuntimeProfile
        get() = when {
            deviceAi.isUsable -> DeviceRuntimeProfile.A_NANO_NATIVE
            localModel.isUsable -> DeviceRuntimeProfile.B_LOCAL_MODEL
            cloud.isUsable && network.isOnline -> DeviceRuntimeProfile.C_CLOUD_HYBRID
            else -> DeviceRuntimeProfile.D_OFFLINE_DETERMINISTIC
        }

    /** True when the device can operate the screen at all. */
    val canControlScreen: Boolean
        get() = accessibilityConnected && gestureDispatchSupported

    val canUnderstandScreenVisually: Boolean
        get() = screenshotSupported && (deviceAi.multimodalSupported || cloud.visionSupported)

    val hasAnyReasoning: Boolean
        get() = deviceAi.isUsable || localModel.isUsable || (cloud.isUsable && network.isOnline)
}

/**
 * Availability of the on-device generative model exposed through ML Kit's GenAI APIs.
 *
 * [featureStatus] mirrors the platform's own model lifecycle: even on a supported
 * device the model may still need downloading, may be downloading right now, or may be
 * transiently busy serving another app. The router therefore re-reads this value
 * instead of caching a single verdict taken at first launch.
 */
@Serializable
data class DeviceAiCapability(
    /** Whether the ML Kit GenAI Prompt API is present on the classpath at all. */
    val promptApiPresent: Boolean = false,
    val featureStatus: AiFeatureStatus = AiFeatureStatus.UNKNOWN,
    val baseModelName: String? = null,
    val multimodalSupported: Boolean = false,
    val structuredOutputSupported: Boolean = false,
    val systemPromptSupported: Boolean = false,
    val tokenLimit: Int = 0,
    val lastError: String? = null,
    val quotaExhausted: Boolean = false,
) {
    val isUsable: Boolean
        get() = promptApiPresent && featureStatus == AiFeatureStatus.AVAILABLE && !quotaExhausted

    val isDownloadable: Boolean
        get() = promptApiPresent && featureStatus == AiFeatureStatus.DOWNLOADABLE
}

/**
 * Mirrors `com.google.mlkit.genai.common.FeatureStatus`, plus the two transient states
 * the platform reports as errors rather than statuses and which Autobile therefore has
 * to model itself: [BUSY] and [UNKNOWN].
 */
enum class AiFeatureStatus {
    UNKNOWN,
    UNAVAILABLE,
    DOWNLOADABLE,
    DOWNLOADING,
    AVAILABLE,
    BUSY,
}

/**
 * Optional bundled local model, for devices with no platform on-device AI but enough
 * compute to run a small model directly. Entirely optional: the product must work
 * without it.
 */
@Serializable
data class LocalModelCapability(
    val supported: Boolean = false,
    val modelInstalled: Boolean = false,
    val modelName: String? = null,
    val modelSizeBytes: Long = 0L,
) {
    val isUsable: Boolean get() = supported && modelInstalled
}

@Serializable
data class CloudCapability(
    val configured: Boolean = false,
    val userConsented: Boolean = false,
    val visionSupported: Boolean = false,
    val endpointLabel: String? = null,
) {
    val isUsable: Boolean get() = configured && userConsented
}

enum class NetworkState {
    UNKNOWN,
    OFFLINE,
    METERED,
    UNMETERED;

    val isOnline: Boolean get() = this == METERED || this == UNMETERED
}

enum class ComputeClass { UNKNOWN, LOW, MEDIUM, HIGH }

/**
 * A condition that limits what the runtime can do. These are surfaced to the user
 * rather than hidden, so the app never appears to support something it cannot.
 */
@Serializable
data class RuntimeRestriction(
    val kind: RestrictionKind,
    val detail: String = "",
)

enum class RestrictionKind {
    ACCESSIBILITY_NOT_ENABLED,
    SCREENSHOT_UNSUPPORTED,
    SECURE_WINDOW,
    BACKGROUND_START_BLOCKED,
    BATTERY_OPTIMIZED,
    DEVICE_AI_UNAVAILABLE,
    DEVICE_AI_QUOTA,
    NETWORK_UNAVAILABLE,
    NOTIFICATION_ACCESS_MISSING,
    OVERLAY_PERMISSION_MISSING,
    UNLOCKED_BOOTLOADER,
}

/**
 * The execution strategy a device qualifies for, derived from its live capabilities.
 *
 * Profiles are ordered by how much of the reasoning stays on the device, and are
 * recomputed from [DeviceCapabilityProfile] rather than stored.
 */
enum class DeviceRuntimeProfile {
    A_NANO_NATIVE,
    B_LOCAL_MODEL,
    C_CLOUD_HYBRID,
    D_OFFLINE_DETERMINISTIC;

    val displayName: String
        get() = when (this) {
            A_NANO_NATIVE -> "On-device AI"
            B_LOCAL_MODEL -> "Local model"
            C_CLOUD_HYBRID -> "Cloud assisted"
            D_OFFLINE_DETERMINISTIC -> "Offline deterministic"
        }

    /**
     * User-facing explanation of what this profile means in practice.
     *
     * The absence of on-device AI is stated plainly rather than hidden: a user whose
     * device escalates to the cloud is entitled to know that before they teach it
     * anything.
     */
    val explanation: String
        get() = when (this) {
            A_NANO_NATIVE -> "Most screen decisions are handled on this device."
            B_LOCAL_MODEL -> "A downloaded local model handles screen decisions."
            C_CLOUD_HYBRID -> "This device has no on-device AI. Difficult decisions are sent to the cloud."
            D_OFFLINE_DETERMINISTIC -> "No AI runtime is available. Only previously learned deterministic steps can run."
        }
}
