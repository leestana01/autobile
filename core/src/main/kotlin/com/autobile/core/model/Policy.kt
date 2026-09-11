package com.autobile.core.model

import kotlinx.serialization.Serializable

/**
 * What the agent is allowed to do inside one application.
 *
 * Defaults are protective and applied before the user configures anything: password
 * managers and authenticators are blocked outright, and financial and health apps
 * require confirmation.
 */
@Serializable
data class AppPolicy(
    val packageName: String,
    val mode: AppPolicyMode,
    val category: AppCategory = AppCategory.OTHER,
    val userSet: Boolean = false,
    val label: String = "",
)

enum class AppPolicyMode {
    ALLOW,
    ASK,
    OBSERVE_ONLY,
    BLOCK;

    val displayName: String
        get() = when (this) {
            ALLOW -> "Allow"
            ASK -> "Ask"
            OBSERVE_ONLY -> "Observe only"
            BLOCK -> "Block"
        }
}

enum class AppCategory {
    BANKING,
    PASSWORD_MANAGER,
    AUTHENTICATOR,
    HEALTH,
    GALLERY,
    MESSAGING,
    BUSINESS,
    OTHER;

    /** The policy applied to an app of this category until the user overrides it. */
    val defaultMode: AppPolicyMode
        get() = when (this) {
            BANKING -> AppPolicyMode.ASK
            PASSWORD_MANAGER -> AppPolicyMode.BLOCK
            AUTHENTICATOR -> AppPolicyMode.BLOCK
            HEALTH -> AppPolicyMode.ASK
            GALLERY -> AppPolicyMode.ASK
            MESSAGING -> AppPolicyMode.ASK
            BUSINESS -> AppPolicyMode.ASK
            OTHER -> AppPolicyMode.ASK
        }
}

/**
 * The risk engine's verdict on a proposed action.
 *
 * The risk engine sits above the AI runtime, not beside it: a model concluding that an
 * action is appropriate does not override a [RiskVerdict.DENY] returned here.
 */
@Serializable
data class RiskDecision(
    val verdict: RiskVerdict,
    val categories: Set<RiskCategory> = emptySet(),
    val reason: String = "",
    val requiresConfirmation: Boolean = false,
    val appPolicy: AppPolicyMode? = null,
)

enum class RiskVerdict { ALLOW, CONFIRM, DENY }

/** User-controlled settings governing what, if anything, may leave the device. */
@Serializable
data class PrivacySettings(
    val cloudEnabled: Boolean = false,
    val allowScreenshotToCloud: Boolean = false,
    val maskSensitiveFields: Boolean = true,
    val cloudEndpoint: String = "",
    val cloudApiKeyPresent: Boolean = false,
    val cloudModel: String = "",
    val cloudVisionModel: String = "",
)

/** Global stop control. While engaged, no skill may execute for any reason. */
@Serializable
data class KillSwitchState(
    val engaged: Boolean = false,
    val engagedAt: Long = 0L,
    val reason: String = "",
)
