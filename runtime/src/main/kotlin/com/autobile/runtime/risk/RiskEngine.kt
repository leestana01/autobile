package com.autobile.runtime.risk

import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.SettingsStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AppPolicyMode
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.RiskCategory
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.RiskVerdict
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent

/**
 * The final say on whether an action may be performed.
 *
 * The risk engine sits above the inference layer rather than beside it. A model
 * concluding that pressing "Send" is the right move does not make it permitted: this
 * class can still refuse, and nothing downstream can overrule that refusal. Keeping the
 * authority here — in deterministic code with no model in the loop — is what makes the
 * safety behaviour auditable and reproducible.
 *
 * Checks are ordered by how absolute they are: the global stop first, then per-app
 * policy, then the action's own risk category against the skill's effective autonomy.
 */
class RiskEngine(
    private val policyStore: AppPolicyStore,
    private val settings: SettingsStore,
) {

    suspend fun evaluate(
        skill: SemanticSkill,
        step: SkillStep,
        targetPackage: String,
        userConfirmedThisRun: Boolean = false,
    ): RiskDecision {
        settings.killSwitch().takeIf { it.engaged }?.let { state ->
            return RiskDecision(
                verdict = RiskVerdict.DENY,
                reason = state.reason.ifBlank { "Automation is stopped" },
            )
        }

        val policy = policyStore.policyFor(targetPackage)
        when (policy.mode) {
            AppPolicyMode.BLOCK -> return RiskDecision(
                verdict = RiskVerdict.DENY,
                reason = "${policy.category.name.lowercase().replace('_', ' ')} apps are blocked",
                appPolicy = policy.mode,
            )

            AppPolicyMode.OBSERVE_ONLY -> if (mutatesState(step)) {
                return RiskDecision(
                    verdict = RiskVerdict.DENY,
                    reason = "this app is set to observe only",
                    appPolicy = policy.mode,
                )
            }

            AppPolicyMode.ASK, AppPolicyMode.ALLOW -> Unit
        }

        val categories = categorise(step) + skill.riskPolicy.categories.filterForStep(step)
        val autonomy = skill.effectiveAutonomy()

        if (autonomy == AutonomyLevel.L0_OBSERVE) {
            return RiskDecision(
                verdict = RiskVerdict.DENY,
                categories = categories,
                reason = "this automation is suspended until you review it",
            )
        }

        val needsConfirmation = when {
            userConfirmedThisRun -> false
            categories.isEmpty() && policy.mode != AppPolicyMode.ASK -> autonomy <= AutonomyLevel.L1_SUGGEST
            categories.isEmpty() -> autonomy < AutonomyLevel.L3_AUTONOMOUS_LOW_RISK
            // A high-impact action is only ever unattended on a skill the user has
            // explicitly trusted. Nothing else earns that, however well it has run.
            else -> autonomy < AutonomyLevel.L4_EXPLICITLY_TRUSTED || skill.riskPolicy.requireConfirmation
        }

        return if (needsConfirmation) {
            RiskDecision(
                verdict = RiskVerdict.CONFIRM,
                categories = categories,
                reason = describe(categories, policy.mode),
                requiresConfirmation = true,
                appPolicy = policy.mode,
            )
        } else {
            RiskDecision(
                verdict = RiskVerdict.ALLOW,
                categories = categories,
                reason = "low risk action",
                appPolicy = policy.mode,
            )
        }
    }

    /**
     * Classifies a step's inherent risk from its declared intent.
     *
     * Deliberately structural rather than inferred: the classification of "this step
     * sends something" must not itself depend on a model that could be wrong.
     */
    fun categorise(step: SkillStep): Set<RiskCategory> = buildSet {
        when (step.intent) {
            StepIntent.SEND -> add(RiskCategory.MESSAGE_SEND)
            StepIntent.SHARE -> add(RiskCategory.EXTERNAL_POST)
            StepIntent.DELETE -> add(RiskCategory.DELETE)
            else -> Unit
        }
        val label = (step.target.intentLabel + " " + step.target.description + " " + step.description).lowercase()
        PURCHASE_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.PURCHASE) }
        PAYMENT_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.PAYMENT) }
        TRANSFER_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.TRANSFER) }
        SUBSCRIPTION_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.SUBSCRIPTION) }
        BOOKING_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.BOOKING) }
        CANCELLATION_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.CANCELLATION) }
        DELETE_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.DELETE) }
        PERMISSION_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.PERMISSION_CHANGE) }
        ACCOUNT_TERMS.firstOrNull { label.contains(it) }?.let { add(RiskCategory.ACCOUNT_CHANGE) }
    }

    /** Whether a step changes anything, as opposed to reading or navigating. */
    fun mutatesState(step: SkillStep): Boolean = when (step.action) {
        is ActionSpec.ReadValue, is ActionSpec.Wait, is ActionSpec.Scroll, ActionSpec.Back, ActionSpec.Home -> false
        else -> step.intent != StepIntent.READ_VALUE && step.intent != StepIntent.SCROLL_TO
    }

    /**
     * Whether a proposed repair changes what a skill means rather than how it finds
     * something.
     *
     * A self-repair may re-point a step at a moved button. It may not quietly turn a
     * step that reads a figure into one that sends it, or one that saves into one that
     * deletes — those need a person to agree, because the cost of getting them wrong is
     * not recoverable by retrying.
     */
    fun isUnsafeMutation(original: SkillStep, patched: SkillStep): Boolean {
        if (original.intent == patched.intent) {
            val before = categorise(original)
            val after = categorise(patched)
            if (after.subtract(before).isNotEmpty()) return true
            return valueSemanticsChanged(original, patched)
        }
        val escalating = patched.intent in ESCALATING_INTENTS && original.intent !in ESCALATING_INTENTS
        return escalating || categorise(patched).subtract(categorise(original)).isNotEmpty()
    }

    /**
     * Detects a repair that silently reads a different field. Reporting gross revenue
     * where net was intended is wrong in a way no amount of successful validation
     * catches, because both readings are perfectly valid numbers.
     */
    private fun valueSemanticsChanged(original: SkillStep, patched: SkillStep): Boolean {
        val before = original.target.valueSemantics ?: return false
        val after = patched.target.valueSemantics ?: return false
        return !before.fieldName.equals(after.fieldName, ignoreCase = true)
    }

    private fun Set<RiskCategory>.filterForStep(step: SkillStep): Set<RiskCategory> =
        if (mutatesState(step)) this else emptySet()

    private fun describe(categories: Set<RiskCategory>, policyMode: AppPolicyMode): String = when {
        categories.contains(RiskCategory.PAYMENT) || categories.contains(RiskCategory.TRANSFER) ->
            "this step moves money"

        categories.contains(RiskCategory.PURCHASE) -> "this step completes a purchase"
        categories.contains(RiskCategory.DELETE) -> "this step deletes something"
        categories.contains(RiskCategory.MESSAGE_SEND) -> "this step sends a message"
        categories.contains(RiskCategory.EXTERNAL_POST) -> "this step posts something others will see"
        categories.contains(RiskCategory.CANCELLATION) -> "this step cancels something"
        categories.contains(RiskCategory.PERMISSION_CHANGE) -> "this step changes a permission"
        categories.contains(RiskCategory.ACCOUNT_CHANGE) -> "this step changes account settings"
        categories.contains(RiskCategory.SUBSCRIPTION) -> "this step changes a subscription"
        categories.contains(RiskCategory.BOOKING) -> "this step makes a booking"
        policyMode == AppPolicyMode.ASK -> "you asked to confirm actions in this app"
        else -> "confirmation required"
    }

    private companion object {
        val ESCALATING_INTENTS = setOf(StepIntent.SEND, StepIntent.SHARE, StepIntent.DELETE, StepIntent.CONFIRM)

        val PURCHASE_TERMS = listOf("purchase", "buy", "checkout", "order now", "결제", "구매", "주문")
        val PAYMENT_TERMS = listOf("pay", "payment", "card", "결제하기")
        val TRANSFER_TERMS = listOf("transfer", "remit", "withdraw", "송금", "이체", "출금")
        val SUBSCRIPTION_TERMS = listOf("subscribe", "subscription", "구독")
        val BOOKING_TERMS = listOf("book", "reserve", "reservation", "예약")
        val CANCELLATION_TERMS = listOf("cancel", "refund", "취소", "환불")
        val DELETE_TERMS = listOf("delete", "remove", "erase", "삭제", "제거")
        val PERMISSION_TERMS = listOf("permission", "allow access", "grant", "권한")
        val ACCOUNT_TERMS = listOf("account settings", "change password", "sign out", "계정", "비밀번호")
    }
}
