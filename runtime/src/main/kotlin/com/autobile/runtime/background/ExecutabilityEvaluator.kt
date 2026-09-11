package com.autobile.runtime.background

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.SettingsStore
import com.autobile.core.model.AppPolicyMode
import com.autobile.core.model.DeviceCapabilityProfile
import com.autobile.core.model.ExecutabilityState
import com.autobile.core.model.SemanticSkill

/**
 * Decides whether a skill can run right now.
 *
 * Android restricts what an app may start from the background, and a locked device
 * blocks whole classes of interaction outright. Those limits are modelled explicitly
 * instead of being discovered halfway through a run and reported as a failure — a task
 * that cannot start yet has not failed, and treating it as failure trains users to
 * ignore the distinction.
 */
class ExecutabilityEvaluator(
    private val context: Context,
    private val policyStore: AppPolicyStore,
    private val settings: SettingsStore,
) {

    suspend fun evaluate(skill: SemanticSkill, profile: DeviceCapabilityProfile): ExecutabilityState {
        if (settings.killSwitch().engaged) return ExecutabilityState.OS_BLOCKED
        if (!profile.canControlScreen) return ExecutabilityState.OS_BLOCKED

        for (packageName in skill.runtimeRequirements.requiredPackages) {
            val policy = policyStore.policyFor(packageName)
            if (policy.mode == AppPolicyMode.BLOCK) return ExecutabilityState.APP_BLOCKED
            if (policy.mode == AppPolicyMode.OBSERVE_ONLY) return ExecutabilityState.APP_BLOCKED
        }

        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (skill.runtimeRequirements.requiresUnlockedDevice && keyguard?.isKeyguardLocked == true) {
            // Distinguishes a screen that is merely off from one the user must unlock:
            // the former resolves on its own, the latter needs the user.
            return if (keyguard.isDeviceSecure) {
                ExecutabilityState.USER_UNLOCK_REQUIRED
            } else {
                ExecutabilityState.DEVICE_LOCKED
            }
        }

        if (skill.riskPolicy.isHighImpact &&
            skill.effectiveAutonomy() < com.autobile.core.model.AutonomyLevel.L4_EXPLICITLY_TRUSTED
        ) {
            val power = context.getSystemService(PowerManager::class.java)
            if (power?.isInteractive == false) {
                // The skill will need confirmation part-way through, and nobody is there
                // to give it. Defer rather than start something that cannot finish.
                return ExecutabilityState.USER_INTERACTION_REQUIRED
            }
        }

        if (skill.runtimeRequirements.requiresNetwork && !profile.network.isOnline) {
            return ExecutabilityState.OS_BLOCKED
        }

        return ExecutabilityState.EXECUTABLE
    }

    /**
     * How long to wait before reconsidering a deferred task.
     *
     * States that resolve on their own are retried quickly; those needing a person are
     * retried slowly, since polling changes nothing until they act.
     */
    fun retryDelayMillis(state: ExecutabilityState): Long = when (state) {
        ExecutabilityState.EXECUTABLE -> 0
        ExecutabilityState.DEVICE_LOCKED -> SHORT_RETRY_MS
        ExecutabilityState.USER_UNLOCK_REQUIRED -> LONG_RETRY_MS
        ExecutabilityState.USER_INTERACTION_REQUIRED -> LONG_RETRY_MS
        ExecutabilityState.OS_BLOCKED -> MEDIUM_RETRY_MS
        ExecutabilityState.APP_BLOCKED -> 0
    }

    private companion object {
        const val SHORT_RETRY_MS = 2 * 60 * 1000L
        const val MEDIUM_RETRY_MS = 10 * 60 * 1000L
        const val LONG_RETRY_MS = 30 * 60 * 1000L
    }
}
