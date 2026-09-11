package com.autobile.runtime.risk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AppCategory
import com.autobile.core.model.AppPolicy
import com.autobile.core.model.AppPolicyMode
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.RiskCategory
import com.autobile.core.model.RiskPolicy
import com.autobile.core.model.RiskVerdict
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConfidence
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.ValueSemantics
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The risk engine is the last thing standing between an inference and an irreversible
 * action, so its refusals are tested as first-class behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class RiskEngineTest {

    private lateinit var context: Context
    private lateinit var policies: AppPolicyStore
    private lateinit var settings: SettingsStore
    private lateinit var engine: RiskEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        policies = AppPolicyStore(AutobileDatabase(context))
        settings = SettingsStore(context)
        settings.setKillSwitch(false)
        engine = RiskEngine(policies, settings)
    }

    private fun skill(
        autonomy: AutonomyLevel = AutonomyLevel.L4_EXPLICITLY_TRUSTED,
        confidence: Float = 0.95f,
        riskPolicy: RiskPolicy = RiskPolicy(requireConfirmation = false),
        steps: List<SkillStep> = emptyList(),
    ) = SemanticSkill(
        id = "s",
        version = 1,
        name = "Test",
        goal = "goal",
        autonomyLevel = autonomy,
        confidence = SkillConfidence(score = confidence),
        riskPolicy = riskPolicy,
        steps = steps,
    )

    private fun step(
        intent: StepIntent = StepIntent.SELECT_ITEM,
        label: String = "Item",
        action: ActionSpec = ActionSpec.Click,
    ) = SkillStep(
        id = "step",
        intent = intent,
        target = TargetSemantics(intentLabel = label, description = label),
        action = action,
    )

    @Test
    fun `the kill switch denies everything`() = runTest {
        settings.setKillSwitch(true, "stopped by the user")
        val decision = engine.evaluate(skill(), step(), "com.example.app")
        assertThat(decision.verdict).isEqualTo(RiskVerdict.DENY)
    }

    @Test
    fun `a blocked app denies even a trusted skill`() = runTest {
        policies.save(AppPolicy("com.example.vault", AppPolicyMode.BLOCK, AppCategory.PASSWORD_MANAGER))
        val decision = engine.evaluate(skill(), step(), "com.example.vault")
        assertThat(decision.verdict).isEqualTo(RiskVerdict.DENY)
    }

    @Test
    fun `an observe-only app allows reading but denies acting`() = runTest {
        policies.save(AppPolicy("com.example.health", AppPolicyMode.OBSERVE_ONLY, AppCategory.HEALTH))

        val read = engine.evaluate(
            skill(),
            step(intent = StepIntent.READ_VALUE, action = ActionSpec.ReadValue("v")),
            "com.example.health",
        )
        val act = engine.evaluate(skill(), step(intent = StepIntent.SEND, label = "Send"), "com.example.health")

        assertThat(read.verdict).isNotEqualTo(RiskVerdict.DENY)
        assertThat(act.verdict).isEqualTo(RiskVerdict.DENY)
    }

    @Test
    fun `a degraded skill is denied regardless of its declared autonomy`() = runTest {
        policies.save(AppPolicy("com.example.app", AppPolicyMode.ALLOW, AppCategory.OTHER))
        val decision = engine.evaluate(
            skill(autonomy = AutonomyLevel.L4_EXPLICITLY_TRUSTED, confidence = 0.05f),
            step(),
            "com.example.app",
        )
        assertThat(decision.verdict).isEqualTo(RiskVerdict.DENY)
    }

    @Test
    fun `sending a message requires confirmation unless the skill is explicitly trusted`() = runTest {
        policies.save(AppPolicy("com.example.chat", AppPolicyMode.ALLOW, AppCategory.OTHER))
        val decision = engine.evaluate(
            skill(autonomy = AutonomyLevel.L3_AUTONOMOUS_LOW_RISK),
            step(intent = StepIntent.SEND, label = "Send"),
            "com.example.chat",
        )
        assertThat(decision.verdict).isEqualTo(RiskVerdict.CONFIRM)
        assertThat(decision.categories).contains(RiskCategory.MESSAGE_SEND)
    }

    @Test
    fun `a low risk read runs unattended on a trusted skill`() = runTest {
        policies.save(AppPolicy("com.example.app", AppPolicyMode.ALLOW, AppCategory.OTHER))
        val decision = engine.evaluate(
            skill(),
            step(intent = StepIntent.READ_VALUE, action = ActionSpec.ReadValue("v")),
            "com.example.app",
        )
        assertThat(decision.verdict).isEqualTo(RiskVerdict.ALLOW)
    }

    @Test
    fun `payment vocabulary is recognised regardless of the declared intent`() {
        val categories = engine.categorise(step(intent = StepIntent.CONFIRM, label = "Pay now"))
        assertThat(categories).contains(RiskCategory.PAYMENT)
    }

    @Test
    fun `korean action vocabulary is recognised`() {
        assertThat(engine.categorise(step(label = "삭제"))).contains(RiskCategory.DELETE)
        assertThat(engine.categorise(step(label = "송금"))).contains(RiskCategory.TRANSFER)
    }

    @Test
    fun `a repair that only relocates an element is safe to apply automatically`() {
        val before = step(intent = StepIntent.SELECT_ITEM, label = "Daily Sales")
        val after = before.copy(
            target = before.target.copy(intentLabel = "Daily Sales", description = "Reports > Daily Sales"),
        )
        assertThat(engine.isUnsafeMutation(before, after)).isFalse()
    }

    @Test
    fun `a repair that turns reading into sending needs the user`() {
        val before = step(intent = StepIntent.READ_VALUE, label = "Net sales")
        val after = step(intent = StepIntent.SEND, label = "Send")
        assertThat(engine.isUnsafeMutation(before, after)).isTrue()
    }

    @Test
    fun `a repair that reads a different field needs the user`() {
        val before = step(intent = StepIntent.READ_VALUE, label = "Net sales").let {
            it.copy(target = it.target.copy(valueSemantics = ValueSemantics("net sales")))
        }
        val after = before.copy(
            target = before.target.copy(valueSemantics = ValueSemantics("gross sales")),
        )
        assertThat(engine.isUnsafeMutation(before, after)).isTrue()
    }

    @Test
    fun `a repair that introduces a delete needs the user`() {
        val before = step(intent = StepIntent.SELECT_ITEM, label = "Item")
        val after = step(intent = StepIntent.SELECT_ITEM, label = "Delete item")
        assertThat(engine.isUnsafeMutation(before, after)).isTrue()
    }

    @Test
    fun `an unknown package defaults to asking rather than allowing`() = runTest {
        val decision = engine.evaluate(
            skill(autonomy = AutonomyLevel.L3_AUTONOMOUS_LOW_RISK),
            step(intent = StepIntent.SEND, label = "Send"),
            "com.unknown.newapp",
        )
        assertThat(decision.verdict).isEqualTo(RiskVerdict.CONFIRM)
    }
}
