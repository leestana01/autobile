package com.autobile.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.autobile.core.model.KillSwitchState
import com.autobile.core.model.PrivacySettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Device-local settings.
 *
 * Cloud access is opt-in and off by default. The cloud is an escalation layer, not a
 * dependency, so nothing leaves the device until the user explicitly turns it on.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("autobile_settings", Context.MODE_PRIVATE)

    private fun changes(): Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> trySend(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observePrivacy(): Flow<PrivacySettings> =
        changes().onStart { emit(null) }.map { privacy() }

    fun observeKillSwitch(): Flow<KillSwitchState> =
        changes().onStart { emit(null) }.map { killSwitch() }

    fun observeOnboarding(): Flow<Boolean> =
        changes().onStart { emit(null) }.map { onboardingComplete }

    fun privacy(): PrivacySettings = PrivacySettings(
        cloudEnabled = prefs.getBoolean(KEY_CLOUD_ENABLED, false),
        allowScreenshotToCloud = prefs.getBoolean(KEY_CLOUD_SCREENSHOT, false),
        maskSensitiveFields = prefs.getBoolean(KEY_MASK_SENSITIVE, true),
        cloudEndpoint = prefs.getString(KEY_CLOUD_ENDPOINT, "").orEmpty(),
        cloudApiKeyPresent = !prefs.getString(KEY_CLOUD_API_KEY, "").isNullOrBlank(),
        cloudModel = prefs.getString(KEY_CLOUD_MODEL, DEFAULT_CLOUD_MODEL).orEmpty(),
        cloudVisionModel = prefs.getString(KEY_CLOUD_VISION_MODEL, DEFAULT_CLOUD_MODEL).orEmpty(),
    )

    fun updatePrivacy(settings: PrivacySettings) = prefs.edit {
        putBoolean(KEY_CLOUD_ENABLED, settings.cloudEnabled)
        putBoolean(KEY_CLOUD_SCREENSHOT, settings.allowScreenshotToCloud)
        putBoolean(KEY_MASK_SENSITIVE, settings.maskSensitiveFields)
        putString(KEY_CLOUD_ENDPOINT, settings.cloudEndpoint)
        putString(KEY_CLOUD_MODEL, settings.cloudModel)
        putString(KEY_CLOUD_VISION_MODEL, settings.cloudVisionModel)
    }

    /**
     * Read separately from [privacy] so the key never enters an observable UI state
     * object. [PrivacySettings.cloudApiKeyPresent] carries only whether one is set.
     */
    fun cloudApiKey(): String = prefs.getString(KEY_CLOUD_API_KEY, "").orEmpty()

    fun setCloudApiKey(key: String) = prefs.edit { putString(KEY_CLOUD_API_KEY, key) }

    fun killSwitch(): KillSwitchState = KillSwitchState(
        engaged = prefs.getBoolean(KEY_KILL_SWITCH, false),
        engagedAt = prefs.getLong(KEY_KILL_SWITCH_AT, 0L),
        reason = prefs.getString(KEY_KILL_SWITCH_REASON, "").orEmpty(),
    )

    fun setKillSwitch(engaged: Boolean, reason: String = "") = prefs.edit {
        putBoolean(KEY_KILL_SWITCH, engaged)
        putLong(KEY_KILL_SWITCH_AT, System.currentTimeMillis())
        putString(KEY_KILL_SWITCH_REASON, reason)
    }

    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING, value) }

    var localModelEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_MODEL, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCAL_MODEL, value) }

    var deviceAiDownloadConsented: Boolean
        get() = prefs.getBoolean(KEY_NANO_DOWNLOAD_CONSENT, false)
        set(value) = prefs.edit { putBoolean(KEY_NANO_DOWNLOAD_CONSENT, value) }

    var showTouchIndicator: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_INDICATOR, true)
        set(value) = prefs.edit { putBoolean(KEY_TOUCH_INDICATOR, value) }

    companion object {
        const val DEFAULT_CLOUD_MODEL = "gemini-2.5-flash"

        private const val KEY_CLOUD_ENABLED = "cloud_enabled"
        private const val KEY_CLOUD_SCREENSHOT = "cloud_screenshot"
        private const val KEY_MASK_SENSITIVE = "mask_sensitive"
        private const val KEY_CLOUD_ENDPOINT = "cloud_endpoint"
        private const val KEY_CLOUD_API_KEY = "cloud_api_key"
        private const val KEY_CLOUD_MODEL = "cloud_model"
        private const val KEY_CLOUD_VISION_MODEL = "cloud_vision_model"
        private const val KEY_KILL_SWITCH = "kill_switch"
        private const val KEY_KILL_SWITCH_AT = "kill_switch_at"
        private const val KEY_KILL_SWITCH_REASON = "kill_switch_reason"
        private const val KEY_ONBOARDING = "onboarding_complete"
        private const val KEY_LOCAL_MODEL = "local_model_enabled"
        private const val KEY_NANO_DOWNLOAD_CONSENT = "nano_download_consent"
        private const val KEY_TOUCH_INDICATOR = "touch_indicator"
    }
}
