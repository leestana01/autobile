package com.autobile.core.common

import java.util.UUID

object Ids {
    fun random(prefix: String): String = "$prefix-${UUID.randomUUID().toString().replace("-", "").take(16)}"

    fun skill(): String = random("skill")
    fun step(): String = random("step")
    fun task(): String = random("task")
    fun trace(): String = random("trace")
    fun event(): String = random("evt")
}
