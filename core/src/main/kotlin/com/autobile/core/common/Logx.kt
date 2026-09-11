package com.autobile.core.common

import android.util.Log

/**
 * Logging facade with mandatory redaction.
 *
 * Autobile keeps a complete execution trail, which means log lines routinely carry
 * text lifted straight off the user's screen. Every such value must pass through
 * [redact] before it reaches logcat or the event store so that credentials, card
 * numbers and one-time codes are never persisted verbatim.
 */
object Logx {
    const val TAG = "Autobile"

    private val sensitivePatterns = listOf(
        Regex("""\b\d{3}-\d{2}-\d{4}\b"""),                       // national id style
        Regex("""\b(?:\d[ -]*?){13,19}\b"""),                      // card numbers
        Regex("""\b[\w.+-]+@[\w-]+\.[\w.]{2,}\b"""),               // emails
        Regex("""\b01[016789][- ]?\d{3,4}[- ]?\d{4}\b"""),         // KR mobile numbers
        Regex("""(?i)\b(otp|code|password|passcode|pin)\b\s*[:=]?\s*\S+"""),
    )

    /** Replaces recognised sensitive substrings with a stable marker. */
    fun redact(value: String?): String {
        var out: String = value.orEmpty()
        if (out.isEmpty()) return ""
        sensitivePatterns.forEach { pattern -> out = pattern.replace(out, "[redacted]") }
        return if (out.length > 240) out.take(240) + "…" else out
    }

    fun d(message: String) = Log.d(TAG, message)
    fun i(message: String) = Log.i(TAG, message)
    fun w(message: String, t: Throwable? = null) = Log.w(TAG, message, t)
    fun e(message: String, t: Throwable? = null) = Log.e(TAG, message, t)
}
