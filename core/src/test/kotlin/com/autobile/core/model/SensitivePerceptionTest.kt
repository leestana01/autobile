package com.autobile.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitivePerceptionTest {
    @Test
    fun `secret node text is excluded from labels and screen text`() {
        val node = UiNode(
            nodeId = "password",
            text = "hunter2",
            hint = "Password",
            sensitive = true,
        )
        val snapshot = ScreenSnapshot(nodes = listOf(node))

        assertThat(node.label()).isEqualTo("Password")
        assertThat(snapshot.allText()).doesNotContain("hunter2")
    }
}
