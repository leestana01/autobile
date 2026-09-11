package com.autobile.runtime.trigger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.core.common.Logx
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TriggerSpec
import com.autobile.runtime.AutobileRuntime
import com.autobile.runtime.agent.ConfirmationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Starts automations in response to notifications.
 *
 * Matching is a funnel, and the order is the point. Structural filters — package, title
 * text, body text — run first and reject almost everything for free. Only a notification
 * that has already passed those filters, and whose trigger asks a question that text
 * matching cannot answer, reaches an inference call.
 *
 * That ordering is what keeps the phone's entire notification stream from being fed to a
 * model. A notification listener sees every message, alert and email on the device, and
 * the routing preference for on-device inference matters more here than anywhere else in
 * the system.
 */
class NotificationTriggerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var router: AiRuntimeRouter? = null

    /** Supplies the router used for semantic classification. */
    fun attachRouter(router: AiRuntimeRouter) {
        this.router = router
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Logx.i("Notification access connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName) return
        // Ongoing notifications are status indicators (music, navigation, downloads) that
        // update continuously; treating each update as an event would fire repeatedly.
        if (notification.isOngoing) return

        if (AutobileRuntime.services == null) return
        val payload = notification.toPayload()
        if (payload.isEmpty()) return

        scope.launch {
            runCatching { evaluate(payload) }
                .onFailure { Logx.w("Notification trigger evaluation failed", it) }
        }
    }

    private suspend fun evaluate(payload: NotificationPayload) {
        val services = AutobileRuntime.services ?: return
        val candidates = services.skillStore.listEnabledSkills()
            .filter { it.trigger is TriggerSpec.Notification }

        for (skill in candidates) {
            val trigger = skill.trigger as TriggerSpec.Notification
            if (!structurallyMatches(trigger, payload)) continue

            val condition = trigger.semanticCondition?.takeIf { it.isNotBlank() }
            val extracted = if (condition == null) {
                emptyMap()
            } else {
                val match = classify(condition, payload) ?: continue
                if (!match.first) continue
                match.second
            }

            services.orchestrator.runSkill(
                skillId = skill.id,
                origin = TaskOrigin.NOTIFICATION_TRIGGER,
                triggerPayload = payload.asMap() + extracted,
                confirmation = ConfirmationMode.AutoDecline,
            )
            // One notification starts at most one automation. Running several at once
            // would have them fighting over the same screen.
            return
        }
    }

    /** Free filters that reject the overwhelming majority of notifications. */
    private fun structurallyMatches(trigger: TriggerSpec.Notification, payload: NotificationPayload): Boolean {
        trigger.packageName?.let { if (payload.packageName != it) return false }
        trigger.titleContains?.let { if (!payload.title.contains(it, ignoreCase = true)) return false }
        trigger.textContains?.let { if (!payload.text.contains(it, ignoreCase = true)) return false }
        trigger.category?.let { if (!payload.category.equals(it, ignoreCase = true)) return false }
        return true
    }

    /**
     * Asks whether the notification means what the trigger is waiting for.
     *
     * Restricted to local runtimes regardless of the user's cloud setting: enabling a
     * delivery-notification trigger is not consent to send every notification the phone
     * receives to a server.
     */
    private suspend fun classify(
        condition: String,
        payload: NotificationPayload,
    ): Pair<Boolean, Map<String, String>>? {
        val router = router ?: AutobileRuntime.services?.aiRouter ?: return null
        val routed = router.infer(
            label = "notification-match",
            schema = AiTasks.notificationMatch,
            prompt = AiTasks.notificationMatchPrompt(
                condition = condition,
                title = payload.title,
                text = payload.text,
                packageName = payload.packageName,
            ),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                minConfidence = NOTIFICATION_CONFIDENCE_THRESHOLD,
                localOnly = true,
            ),
        )
        val match = routed.value ?: return null
        return match.matches to match.extracted
    }

    private fun StatusBarNotification.toPayload(): NotificationPayload {
        val extras = notification?.extras
        return NotificationPayload(
            packageName = packageName.orEmpty(),
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = listOfNotNull(
                extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            ).distinct().joinToString(" "),
            category = notification?.category.orEmpty(),
            postedAt = postTime,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_CONFIDENCE_THRESHOLD = 0.6f
    }
}

data class NotificationPayload(
    val packageName: String,
    val title: String,
    val text: String,
    val category: String,
    val postedAt: Long,
) {
    fun isEmpty(): Boolean = title.isBlank() && text.isBlank()

    fun asMap(): Map<String, String> = mapOf(
        "notification.package" to packageName,
        "notification.title" to title,
        "notification.text" to text,
    )
}
