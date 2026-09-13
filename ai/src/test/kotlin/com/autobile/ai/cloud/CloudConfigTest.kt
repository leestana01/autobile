package com.autobile.ai.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudConfigTest {
    @Test
    fun `configured HTTPS endpoint is usable`() {
        assertThat(CloudConfig(enabled = true, apiKey = "key").isUsable).isTrue()
    }

    @Test
    fun `credentials are never sent to a cleartext endpoint`() {
        val config = CloudConfig(
            enabled = true,
            endpoint = "http://example.test/v1beta",
            apiKey = "key",
        )

        assertThat(config.isUsable).isFalse()
    }

    @Test
    fun `malformed endpoint is unusable`() {
        assertThat(CloudConfig(enabled = true, endpoint = "not a URL", apiKey = "key").isUsable).isFalse()
    }
}
