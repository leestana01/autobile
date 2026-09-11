package com.autobile.runtime.capability

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.autobile.ai.cloud.CloudConfig
import com.autobile.ai.local.LocalModelProvider
import com.autobile.ai.mlkit.MLKitGeminiNanoProvider
import com.autobile.core.common.TimeSource
import com.autobile.core.model.CloudCapability
import com.autobile.core.model.ComputeClass
import com.autobile.core.model.DeviceAiCapability
import com.autobile.core.model.DeviceCapabilityProfile
import com.autobile.core.model.NetworkState
import com.autobile.core.model.RestrictionKind
import com.autobile.core.model.RuntimeRestriction
import com.autobile.runtime.accessibility.AccessibilityBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Works out what this device can actually do.
 *
 * Capabilities are probed, never assumed. Two devices on the same Android version can
 * differ completely in on-device AI support, and a single device's answer changes over
 * its lifetime: a model finishes downloading, the user revokes accessibility access, the
 * network drops, a quota is exhausted. Anything cached from first launch would be wrong
 * by the time it mattered.
 *
 * Detected limitations are recorded as [RuntimeRestriction]s and surfaced to the user
 * rather than hidden, so the app never appears to support something it cannot do.
 */
class CapabilityDetector(
    private val context: Context,
    private val deviceAi: MLKitGeminiNanoProvider,
    private val localModel: LocalModelProvider,
    private val cloudConfig: () -> CloudConfig,
    private val time: TimeSource = TimeSource.System,
) {

    private val _profile = MutableStateFlow(DeviceCapabilityProfile())
    val profile: StateFlow<DeviceCapabilityProfile> = _profile.asStateFlow()

    suspend fun detect(): DeviceCapabilityProfile {
        val accessibilityConnected = AccessibilityBridge.connected.value
        val granted = AccessibilityBridge.require()?.grantedCapabilities()
        val enabledInSettings = AccessibilityBridge.isEnabledInSettings(context)
        val deviceAiCapability = probeDeviceAi()
        val network = detectNetwork()
        val cloud = cloudConfig().let {
            CloudCapability(
                configured = it.endpoint.isNotBlank() && it.apiKey.isNotBlank(),
                userConsented = it.enabled,
                visionSupported = it.allowImages,
                endpointLabel = it.endpointLabel,
            )
        }

        val profile = DeviceCapabilityProfile(
            apiLevel = Build.VERSION.SDK_INT,
            accessibilityConnected = accessibilityConnected,
            gestureDispatchSupported = granted?.canPerformGestures == true,
            screenshotSupported = granted?.canTakeScreenshot == true,
            notificationAccessGranted = isNotificationAccessGranted(),
            overlayGranted = Settings.canDrawOverlays(context),
            deviceAi = deviceAiCapability,
            localModel = localModel.describeInstallation(),
            cloud = cloud,
            network = network,
            computeClass = detectComputeClass(),
            restrictions = buildRestrictions(
                accessibilityConnected = accessibilityConnected,
                enabledInSettings = enabledInSettings,
                screenshotGranted = granted?.canTakeScreenshot != false,
                deviceAi = deviceAiCapability,
                network = network,
            ),
            evaluatedAt = time.nowMillis(),
        )
        _profile.value = profile
        return profile
    }

    /**
     * Probes on-device AI, tolerating every way it can be absent.
     *
     * A device without the backing service fails at class resolution rather than
     * returning a status, so the probe treats that as an ordinary configuration rather
     * than an error.
     */
    private suspend fun probeDeviceAi(): DeviceAiCapability =
        runCatching { deviceAi.probe() }.getOrElse { error ->
            DeviceAiCapability(
                promptApiPresent = error !is LinkageError,
                featureStatus = com.autobile.core.model.AiFeatureStatus.UNAVAILABLE,
                lastError = error.message,
            )
        }

    private fun detectNetwork(): NetworkState {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkState.UNKNOWN
        val network = manager.activeNetwork ?: return NetworkState.OFFLINE
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkState.OFFLINE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkState.OFFLINE
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkState.UNMETERED
        } else {
            NetworkState.METERED
        }
    }

    /**
     * Classifies the device's capacity for local inference.
     *
     * Memory is used as the proxy because it is the binding constraint for running a
     * model locally, and unlike sustained CPU performance it can be read cheaply and
     * reliably.
     */
    private fun detectComputeClass(): ComputeClass {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return ComputeClass.UNKNOWN
        val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        val totalGb = info.totalMem / BYTES_PER_GB
        return when {
            manager.isLowRamDevice -> ComputeClass.LOW
            totalGb >= HIGH_RAM_GB -> ComputeClass.HIGH
            totalGb >= MEDIUM_RAM_GB -> ComputeClass.MEDIUM
            else -> ComputeClass.LOW
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.contains(context.packageName)
    }

    private fun buildRestrictions(
        accessibilityConnected: Boolean,
        enabledInSettings: Boolean,
        screenshotGranted: Boolean,
        deviceAi: DeviceAiCapability,
        network: NetworkState,
    ): List<RuntimeRestriction> = buildList {
        if (!accessibilityConnected) {
            add(
                RuntimeRestriction(
                    RestrictionKind.ACCESSIBILITY_NOT_ENABLED,
                    if (enabledInSettings) {
                        "Accessibility access is enabled but not yet connected"
                    } else {
                        "Autobile needs accessibility access to see and control the screen"
                    },
                ),
            )
        }
        if (accessibilityConnected && !screenshotGranted) {
            add(
                RuntimeRestriction(
                    RestrictionKind.SCREENSHOT_UNSUPPORTED,
                    "This device did not grant screen capture, so steps that need to see the screen will be skipped",
                ),
            )
        }
        if (!isNotificationAccessGranted()) {
            add(
                RuntimeRestriction(
                    RestrictionKind.NOTIFICATION_ACCESS_MISSING,
                    "Notification triggers need notification access",
                ),
            )
        }
        if (!Settings.canDrawOverlays(context)) {
            add(
                RuntimeRestriction(
                    RestrictionKind.OVERLAY_PERMISSION_MISSING,
                    "Showing what the agent is doing needs the display-over-apps permission",
                ),
            )
        }
        if (!deviceAi.isUsable) {
            add(
                RuntimeRestriction(
                    RestrictionKind.DEVICE_AI_UNAVAILABLE,
                    deviceAi.lastError ?: "On-device AI is not available on this device",
                ),
            )
        }
        if (deviceAi.quotaExhausted) {
            add(RuntimeRestriction(RestrictionKind.DEVICE_AI_QUOTA, "On-device AI quota is exhausted for now"))
        }
        if (!network.isOnline) {
            add(RuntimeRestriction(RestrictionKind.NETWORK_UNAVAILABLE, "No network connection"))
        }
    }

    private companion object {
        const val BYTES_PER_GB = 1024L * 1024 * 1024
        const val HIGH_RAM_GB = 8
        const val MEDIUM_RAM_GB = 5
    }
}
