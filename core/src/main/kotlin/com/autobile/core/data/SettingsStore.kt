package com.autobile.core.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.autobile.core.model.KillSwitchState
import com.autobile.core.model.PrivacySettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local settings.
 *
 * Cloud access is opt-in and off by default. The cloud is an escalation layer, not a
 * dependency, so nothing leaves the device until the user explicitly turns it on.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("autobile_settings", Context.MODE_PRIVATE)
    private val credentialCipher = CredentialCipher()

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
        // Masking is a non-optional privacy boundary. Older builds exposed a switch;
        // retaining the field keeps stored settings compatible without honoring opt-out.
        maskSensitiveFields = true,
        cloudEndpoint = prefs.getString(KEY_CLOUD_ENDPOINT, "").orEmpty(),
        cloudApiKeyPresent = !prefs.getString(KEY_CLOUD_API_KEY, "").isNullOrBlank(),
        cloudModel = prefs.getString(KEY_CLOUD_MODEL, DEFAULT_CLOUD_MODEL).orEmpty(),
        cloudVisionModel = prefs.getString(KEY_CLOUD_VISION_MODEL, DEFAULT_CLOUD_MODEL).orEmpty(),
    )

    fun updatePrivacy(settings: PrivacySettings) = prefs.edit {
        putBoolean(KEY_CLOUD_ENABLED, settings.cloudEnabled)
        putBoolean(KEY_CLOUD_SCREENSHOT, settings.allowScreenshotToCloud)
        remove(KEY_MASK_SENSITIVE)
        putString(KEY_CLOUD_ENDPOINT, settings.cloudEndpoint)
        putString(KEY_CLOUD_MODEL, settings.cloudModel)
        putString(KEY_CLOUD_VISION_MODEL, settings.cloudVisionModel)
    }

    /**
     * Read separately from [privacy] so the key never enters an observable UI state
     * object. [PrivacySettings.cloudApiKeyPresent] carries only whether one is set.
     */
    fun cloudApiKey(): String {
        val stored = prefs.getString(KEY_CLOUD_API_KEY, "").orEmpty()
        if (stored.isBlank()) return ""
        if (stored.startsWith(CredentialCipher.PREFIX)) {
            return credentialCipher.decrypt(stored).getOrDefault("")
        }

        // v0.1 stored this value as plain SharedPreferences text. Upgrade it in place
        // the first time it is read, while still returning the credential for this run.
        if (!setCloudApiKey(stored)) {
            prefs.edit { remove(KEY_CLOUD_API_KEY) }
        }
        return stored
    }

    /** Stores cloud credentials encrypted by a non-exportable Android Keystore key. */
    fun setCloudApiKey(key: String): Boolean {
        if (key.isBlank()) {
            prefs.edit { remove(KEY_CLOUD_API_KEY) }
            return true
        }
        val encrypted = credentialCipher.encrypt(key).getOrElse { return false }
        prefs.edit { putString(KEY_CLOUD_API_KEY, encrypted) }
        return true
    }

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

/** AES-GCM envelope whose key material never leaves Android Keystore. */
private class CredentialCipher {
    fun encrypt(value: String): Result<String> = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val envelope = ByteBuffer.allocate(Int.SIZE_BYTES + cipher.iv.size + ciphertext.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        PREFIX + Base64.encodeToString(envelope, Base64.NO_WRAP)
    }

    fun decrypt(value: String): Result<String> = runCatching {
        require(value.startsWith(PREFIX))
        val envelope = ByteBuffer.wrap(Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP))
        val ivLength = envelope.int
        require(ivLength in MIN_IV_BYTES..MAX_IV_BYTES && envelope.remaining() > ivLength)
        val iv = ByteArray(ivLength).also(envelope::get)
        val ciphertext = ByteArray(envelope.remaining()).also(envelope::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val PREFIX = "keystore:v1:"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "autobile.cloud.credential"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val MIN_IV_BYTES = 12
        private const val MAX_IV_BYTES = 32
    }
}
