package com.autobile.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Runtime profiles are derived from live capabilities, never assumed or cached. */
class CapabilityProfileTest {

    private val nanoAvailable = DeviceAiCapability(
        promptApiPresent = true,
        featureStatus = AiFeatureStatus.AVAILABLE,
        multimodalSupported = true,
    )

    @Test
    fun `nano availability selects profile A`() {
        val profile = DeviceCapabilityProfile(deviceAi = nanoAvailable, network = NetworkState.UNMETERED)
        assertThat(profile.runtimeProfile).isEqualTo(DeviceRuntimeProfile.A_NANO_NATIVE)
    }

    @Test
    fun `local model selects profile B when nano is missing`() {
        val profile = DeviceCapabilityProfile(
            localModel = LocalModelCapability(supported = true, modelInstalled = true),
            network = NetworkState.UNMETERED,
        )
        assertThat(profile.runtimeProfile).isEqualTo(DeviceRuntimeProfile.B_LOCAL_MODEL)
    }

    @Test
    fun `consented cloud with network selects profile C`() {
        val profile = DeviceCapabilityProfile(
            cloud = CloudCapability(configured = true, userConsented = true),
            network = NetworkState.METERED,
        )
        assertThat(profile.runtimeProfile).isEqualTo(DeviceRuntimeProfile.C_CLOUD_HYBRID)
    }

    @Test
    fun `no reasoning runtime falls back to profile D`() {
        val profile = DeviceCapabilityProfile(
            cloud = CloudCapability(configured = true, userConsented = true),
            network = NetworkState.OFFLINE,
        )
        assertThat(profile.runtimeProfile).isEqualTo(DeviceRuntimeProfile.D_OFFLINE_DETERMINISTIC)
        assertThat(profile.hasAnyReasoning).isFalse()
    }

    @Test
    fun `quota exhaustion makes nano unusable without changing feature status`() {
        val capability = nanoAvailable.copy(quotaExhausted = true)
        assertThat(capability.featureStatus).isEqualTo(AiFeatureStatus.AVAILABLE)
        assertThat(capability.isUsable).isFalse()
    }
}
