package com.autobile.core.common

/** Injectable clock so scheduling and history logic stay unit-testable. */
fun interface TimeSource {
    fun nowMillis(): Long

    companion object {
        val System: TimeSource = TimeSource { java.lang.System.currentTimeMillis() }
    }
}
