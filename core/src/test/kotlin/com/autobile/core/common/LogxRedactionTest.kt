package com.autobile.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Screen text reaches the execution trail, so redaction has to be dependable. */
class LogxRedactionTest {

    @Test
    fun `card numbers are redacted`() {
        assertThat(Logx.redact("card 4111 1111 1111 1111 used")).doesNotContain("4111")
    }

    @Test
    fun `email addresses are redacted`() {
        assertThat(Logx.redact("sent to user@example.com")).doesNotContain("user@example.com")
    }

    @Test
    fun `one time codes are redacted`() {
        assertThat(Logx.redact("your OTP: 384291")).doesNotContain("384291")
    }

    @Test
    fun `ordinary screen text survives redaction`() {
        assertThat(Logx.redact("Net sales 2,481,000 KRW")).isEqualTo("Net sales 2,481,000 KRW")
    }

    @Test
    fun `long values are truncated so history rows stay bounded`() {
        assertThat(Logx.redact("x".repeat(500))).hasLength(241)
    }

    @Test
    fun `null is safe`() {
        assertThat(Logx.redact(null)).isEmpty()
    }
}
