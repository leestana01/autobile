package com.autobile.ai.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Small models rarely return clean JSON. Every response recovered here is a cloud
 * escalation that does not happen, so the recovery paths are worth testing explicitly.
 */
class JsonExtractorTest {

    @Test
    fun `plain json is parsed`() {
        val json = JsonExtractor.extract("""{"index": 2, "confidence": 0.9}""")
        assertThat(json?.intOr("index")).isEqualTo(2)
    }

    @Test
    fun `markdown fences are stripped`() {
        val json = JsonExtractor.extract("```json\n{\"index\": 5}\n```")
        assertThat(json?.intOr("index")).isEqualTo(5)
    }

    @Test
    fun `surrounding prose is discarded`() {
        val json = JsonExtractor.extract("Sure! Here you go: {\"index\": 1} Hope that helps.")
        assertThat(json?.intOr("index")).isEqualTo(1)
    }

    @Test
    fun `trailing commas are repaired`() {
        val json = JsonExtractor.extract("""{"index": 3, "reason": "ok",}""")
        assertThat(json?.stringOr("reason")).isEqualTo("ok")
    }

    @Test
    fun `single quoted keys and values are repaired`() {
        val json = JsonExtractor.extract("{'index': 4, 'reason': 'menu item'}")
        assertThat(json?.intOr("index")).isEqualTo(4)
        assertThat(json?.stringOr("reason")).isEqualTo("menu item")
    }

    @Test
    fun `braces inside string values do not truncate the object`() {
        val json = JsonExtractor.extract("""prefix {"reason": "tap {Reports}", "index": 7} suffix""")
        assertThat(json?.intOr("index")).isEqualTo(7)
        assertThat(json?.stringOr("reason")).isEqualTo("tap {Reports}")
    }

    @Test
    fun `non json output yields null rather than a partial object`() {
        assertThat(JsonExtractor.extract("I could not determine the answer.")).isNull()
    }

    @Test
    fun `blank and null input are handled`() {
        assertThat(JsonExtractor.extract(null)).isNull()
        assertThat(JsonExtractor.extract("   ")).isNull()
    }

    @Test
    fun `missing fields fall back to defaults instead of throwing`() {
        val json = JsonExtractor.extract("""{"index": 1}""")!!
        assertThat(json.stringOr("reason", "none")).isEqualTo("none")
        assertThat(json.floatOr("confidence", -1f)).isEqualTo(-1f)
        assertThat(json.boolOr("matches", true)).isTrue()
        assertThat(json.stringList("categories")).isEmpty()
    }

    @Test
    fun `json null is treated as absent`() {
        val json = JsonExtractor.extract("""{"reason": null}""")!!
        assertThat(json.stringOr("reason", "fallback")).isEqualTo("fallback")
    }
}
