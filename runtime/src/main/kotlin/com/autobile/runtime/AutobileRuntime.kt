package com.autobile.runtime

import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.core.data.HistoryStore
import com.autobile.core.data.SettingsStore
import com.autobile.core.data.SkillStore
import com.autobile.runtime.agent.AgentOrchestrator
import com.autobile.runtime.trigger.TriggerScheduler

/**
 * The services a background entry point needs.
 *
 * Android creates workers and listener services itself, so they cannot be given
 * dependencies through a constructor. Rather than have each one rebuild the runtime —
 * which would produce several independent copies of state that is meant to be shared —
 * the application publishes a single instance here during startup and the entry points
 * read it.
 */
interface AutobileServices {
    val aiRouter: AiRuntimeRouter
    val orchestrator: AgentOrchestrator
    val skillStore: SkillStore
    val historyStore: HistoryStore
    val settings: SettingsStore
    val triggerScheduler: TriggerScheduler
}

/**
 * Holder for the process-wide service instance.
 *
 * A background entry point can be created before or after the application finishes
 * starting up, so [services] is nullable and callers are expected to handle its absence
 * by doing nothing rather than by constructing a replacement.
 */
object AutobileRuntime {

    @Volatile
    private var instance: AutobileServices? = null

    fun install(services: AutobileServices) {
        instance = services
    }

    val services: AutobileServices? get() = instance
}
