package com.autobile.runtime.executor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.ai.task.ElementMatch
import com.autobile.ai.task.ExtractedValue
import com.autobile.ai.task.OutcomeCheck
import com.autobile.ai.task.RecoveryAction
import com.autobile.ai.task.RecoveryProposal
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.data.SkillStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AgentTask
import com.autobile.core.model.AppCategory
import com.autobile.core.model.AppPolicy
import com.autobile.core.model.AppPolicyMode
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.Bounds
import com.autobile.core.model.Condition
import com.autobile.core.model.ConditionKind
import com.autobile.core.model.Direction
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.ExecutionEventType
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.FallbackPolicy
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.OutcomeStatus
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConfidence
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.UiNode
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.core.model.ValueSemantics
import com.autobile.runtime.FakeScreen
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.recovery.SelfHealingEngine
import com.autobile.runtime.resolver.ExecutionResolver
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.routerWith
import com.autobile.runtime.screen
import com.autobile.runtime.validation.ValidationEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The execution loop is where a wrong decision presses a real button, so its behaviour
 * is asserted directly against a fake screen rather than inferred from its parts.
 */
@RunWith(RobolectricTestRunner::class)
class SkillExecutorTest {

    private lateinit var context: Context
    private lateinit var skillStore: SkillStore
    private lateinit var riskEngine: RiskEngine
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val database = AutobileDatabase(context)
        skillStore = SkillStore(database)
        settings = SettingsStore(context)
        settings.setKillSwitch(false)
        riskEngine = RiskEngine(AppPolicyStore(database), settings)
    }

    private fun executor(screen: FakeScreen, provider: ScriptedProvider): SkillExecutor {
        val router = routerWith(provider)
        return SkillExecutor(
            perception = screen,
            controller = screen,
            resolver = ExecutionResolver(router),
            validation = ValidationEngine(router),
            healing = SelfHealingEngine(router, riskEngine),
            riskEngine = riskEngine,
            router = router,
            skillStore = skillStore,
        )
    }

    /** Records the trail and approves whatever it is asked, unless told otherwise. */
    private class Recorder(
        override val taskId: String = "task",
        private val approve: Boolean = true,
        /** Cancels once this many steps have started. */
        private val cancelAfter: Int = Int.MAX_VALUE,
    ) : ExecutionObserver {
        val events = mutableListOf<ExecutionEvent>()
        val patches = mutableListOf<String>()
        var confirmationsRequested = 0
            private set
        private var stepsStarted = 0

        override suspend fun onEvent(event: ExecutionEvent) {
            events += event
        }

        override suspend fun onStepStarted(index: Int, step: SkillStep) {
            stepsStarted++
        }

        override suspend fun requestConfirmation(step: SkillStep, decision: RiskDecision): Boolean {
            confirmationsRequested++
            return approve
        }

        override suspend fun onPatchProposed(patchId: String, summary: String, needsConfirmation: Boolean) {
            patches += summary
        }

        override fun isCancelled(): Boolean = stepsStarted > cancelAfter

        fun typesOf(type: ExecutionEventType) = events.filter { it.type == type }
    }

    private fun task(skill: SemanticSkill) = AgentTask(
        id = "task",
        goal = skill.goal,
        origin = TaskOrigin.MANUAL,
        skillId = skill.id,
    )

    private fun skill(
        steps: List<SkillStep>,
        postconditions: List<Condition> = emptyList(),
        autonomy: AutonomyLevel = AutonomyLevel.L4_EXPLICITLY_TRUSTED,
    ) = SemanticSkill(
        id = "skill",
        version = 1,
        name = "Test automation",
        goal = "do the thing",
        steps = steps,
        postconditions = postconditions,
        autonomyLevel = autonomy,
        confidence = SkillConfidence(score = 0.9f),
        riskPolicy = com.autobile.core.model.RiskPolicy(requireConfirmation = false),
    )

    private fun clickStep(
        id: String = "s1",
        label: String = "Daily Sales",
        resourceId: String? = "com.example:id/daily",
        validation: ValidationSpec = ValidationSpec(mode = ValidationMode.NONE),
        expected: ExpectedState = ExpectedState(),
        retries: Int = 0,
    ) = SkillStep(
        id = id,
        intent = StepIntent.SELECT_ITEM,
        target = TargetSemantics(
            intentLabel = label,
            description = label,
            locators = listOfNotNull(resourceId?.let { Locator(LocatorKind.RESOURCE_ID, it) }),
        ),
        action = ActionSpec.Click,
        expectedState = expected,
        validation = validation,
        fallback = FallbackPolicy(maxRetries = retries),
    )

    @Test
    fun `a deterministic run completes without any inference or cloud call`() = runTest {
        val provider = ScriptedProvider()
        val screen = FakeScreen(
            screen(nodes = arrayOf(node("a", text = "Daily Sales", resourceId = "com.example:id/daily"))),
        )
        val skill = skill(listOf(clickStep()))
        val recorder = Recorder()

        val outcome = executor(screen, provider).execute(skill, task(skill), recorder)

        assertThat(outcome.status).isEqualTo(OutcomeStatus.SUCCESS)
        assertThat(outcome.cloudCalls).isEqualTo(0)
        assertThat(outcome.deviceAiCalls).isEqualTo(0)
        assertThat(provider.requestedLabels).isEmpty()
        assertThat(screen.clicked).containsExactly("a")
    }

    @Test
    fun `a protected screen blocks the run instead of being worked around`() = runTest {
        val screen = FakeScreen(perception = PerceptionResult.BlockedSecureWindow("com.bank.app"))
        val skill = skill(listOf(clickStep()))
        val recorder = Recorder()

        val outcome = executor(screen, ScriptedProvider()).execute(skill, task(skill), recorder)

        assertThat(outcome.status).isEqualTo(OutcomeStatus.BLOCKED)
        assertThat(screen.clicked).isEmpty()
    }

    @Test
    fun `the executor never writes a terminal task event`() = runTest {
        // Exactly one terminal event per run is written by the orchestrator, so the
        // executor emitting its own would duplicate every failure in the history view.
        val screen = FakeScreen(screen(nodes = arrayOf(node("a", text = "Something else"))))
        val skill = skill(listOf(clickStep(resourceId = null)))
        val recorder = Recorder()

        executor(screen, ScriptedProvider()).execute(skill, task(skill), recorder)

        assertThat(recorder.typesOf(ExecutionEventType.TASK_COMPLETED)).isEmpty()
        assertThat(recorder.typesOf(ExecutionEventType.TASK_FAILED)).isEmpty()
        assertThat(recorder.typesOf(ExecutionEventType.STEP_FAILED)).isNotEmpty()
    }

    @Test
    fun `a declined confirmation stops the run`() = runTest {
        val screen = FakeScreen(screen(nodes = arrayOf(node("a", text = "Send", resourceId = "com.example:id/send"))))
        val sendStep = clickStep(label = "Send", resourceId = "com.example:id/send").copy(intent = StepIntent.SEND)
        val skill = skill(listOf(sendStep), autonomy = AutonomyLevel.L3_AUTONOMOUS_LOW_RISK)
        val recorder = Recorder(approve = false)

        val outcome = executor(screen, ScriptedProvider()).execute(skill, task(skill), recorder)

        assertThat(recorder.confirmationsRequested).isEqualTo(1)
        assertThat(outcome.status).isEqualTo(OutcomeStatus.CANCELLED)
        assertThat(screen.clicked).isEmpty()
    }

    @Test
    fun `an app blocked by policy stops the run before acting`() = runTest {
        AppPolicyStore(AutobileDatabase(context))
            .save(AppPolicy("com.bank.app", AppPolicyMode.BLOCK, AppCategory.BANKING))
        val screen = FakeScreen(
            screen(
                packageName = "com.bank.app",
                nodes = arrayOf(node("a", text = "Transfer", resourceId = "com.bank:id/transfer")),
            ),
        )
        val skill = skill(listOf(clickStep(label = "Transfer", resourceId = "com.bank:id/transfer")))

        val outcome = executor(screen, ScriptedProvider()).execute(skill, task(skill), Recorder())

        assertThat(outcome.status).isEqualTo(OutcomeStatus.BLOCKED)
        assertThat(screen.clicked).isEmpty()
    }

    @Test
    fun `unmet post-conditions report partial even when every step succeeded`() = runTest {
        val screen = FakeScreen(
            screen(nodes = arrayOf(node("a", text = "Daily Sales", resourceId = "com.example:id/daily"))),
        )
        val skill = skill(
            steps = listOf(clickStep()),
            postconditions = listOf(
                Condition(description = "report was sent", kind = ConditionKind.STRUCTURAL, requiredTexts = listOf("Sent")),
            ),
        )

        val outcome = executor(screen, ScriptedProvider()).execute(skill, task(skill), Recorder())

        assertThat(outcome.completedSteps).isEqualTo(1)
        assertThat(outcome.status).isEqualTo(OutcomeStatus.PARTIAL)
        assertThat(outcome.goalValidated).isFalse()
    }

    @Test
    fun `a value beside its label is read without inference`() = runTest {
        val provider = ScriptedProvider()
        val screen = FakeScreen(
            screen(
                nodes = arrayOf(
                    node("label", text = "Net sales", bounds = Bounds(40, 200, 300, 260), clickable = false),
                    node("value", text = "2,481,000", bounds = Bounds(400, 200, 700, 260), clickable = false),
                ),
            ),
        )
        val readStep = SkillStep(
            id = "read",
            intent = StepIntent.READ_VALUE,
            target = TargetSemantics(
                intentLabel = "Net sales",
                description = "Net sales",
                locators = emptyList(),
                valueSemantics = ValueSemantics("Net sales"),
            ),
            action = ActionSpec.ReadValue("netSales"),
            validation = ValidationSpec(mode = ValidationMode.NONE),
        )

        val outcome = executor(screen, provider).execute(skill(listOf(readStep)), task(skill(listOf(readStep))), Recorder())

        assertThat(outcome.status).isEqualTo(OutcomeStatus.SUCCESS)
        assertThat(outcome.stepResults.single().extractedValue).isEqualTo("2,481,000")
        assertThat(provider.requestedLabels).doesNotContain("value-extraction")
    }

    @Test
    fun `a relocated target is recovered and the repair is proposed for review`() = runTest {
        // No element-match answer is scripted, so the first attempt genuinely cannot
        // find the target and the recovery path is the only way the run can succeed.
        val provider = ScriptedProvider()
            .answerWith("recovery-proposal", RecoveryProposal(RecoveryAction.TAP, 0, "", "Reports holds sales", 0.8f))
        val screen = FakeScreen(
            screen(nodes = arrayOf(node("reports", text = "Reports", resourceId = "com.example:id/reports"))),
            // After the recovery tap, the target the step was looking for appears.
            nextScreen = screen(
                nodes = arrayOf(node("daily", text = "Daily Sales", resourceId = "com.example:id/daily")),
            ),
        )
        val skill = skill(listOf(clickStep(retries = 2)))
        val recorder = Recorder()

        val outcome = executor(screen, provider).execute(skill, task(skill), recorder)

        assertThat(outcome.status).isEqualTo(OutcomeStatus.SUCCESS)
        assertThat(outcome.stepResults.single().recovered).isTrue()
        assertThat(recorder.patches).isNotEmpty()
        assertThat(skillStore.pendingPatches()).isNotEmpty()
    }

    @Test
    fun `a semantic outcome nobody can verify fails the step`() = runTest {
        val screen = FakeScreen(
            screen(nodes = arrayOf(node("a", text = "Send", resourceId = "com.example:id/send"))),
        )
        val step = clickStep(
            label = "Send",
            resourceId = "com.example:id/send",
            validation = ValidationSpec(mode = ValidationMode.SEMANTIC, expectation = "the message was sent"),
        )
        val skill = skill(listOf(step))

        val outcome = executor(screen, ScriptedProvider(available = false)).execute(skill, task(skill), Recorder())

        assertThat(outcome.status).isEqualTo(OutcomeStatus.PARTIAL)
        assertThat(screen.clicked).containsExactly("a")
    }

    @Test
    fun `a run with no reasoning runtime is postponed rather than failed`() = runTest {
        val screen = FakeScreen(screen(nodes = arrayOf(node("a", text = "Reports"))))
        val skill = skill(listOf(clickStep(resourceId = null)))

        val outcome = executor(screen, ScriptedProvider(available = false)).execute(skill, task(skill), Recorder())

        assertThat(outcome.status).isEqualTo(OutcomeStatus.DEFERRED)
        assertThat(outcome.message).contains("Waiting for a runtime")
    }

    @Test
    fun `a target a runtime examined and rejected fails rather than postponing`() = runTest {
        val provider = ScriptedProvider()
            .answerWith("element-match", ElementMatch(index = -1, confidence = 0.9f, reason = "absent"))
        val screen = FakeScreen(screen(nodes = arrayOf(node("a", text = "Reports"))))
        val skill = skill(listOf(clickStep(resourceId = null)))

        val outcome = executor(screen, provider).execute(skill, task(skill), Recorder())

        assertThat(outcome.status).isEqualTo(OutcomeStatus.PARTIAL)
        assertThat(outcome.goalValidated).isFalse()
    }

    @Test
    fun `an optional step that fails does not stop the run`() = runTest {
        val screen = FakeScreen(
            screen(nodes = arrayOf(node("a", text = "Daily Sales", resourceId = "com.example:id/daily"))),
        )
        val skill = skill(
            listOf(
                clickStep(id = "missing", label = "Dismiss tip", resourceId = "com.example:id/tip").copy(optional = true),
                clickStep(),
            ),
        )

        val outcome = executor(screen, ScriptedProvider()).execute(skill, task(skill), Recorder())

        assertThat(outcome.status).isEqualTo(OutcomeStatus.SUCCESS)
        assertThat(screen.clicked).containsExactly("a")
    }

    @Test
    fun `cancelling between steps stops before the next action`() = runTest {
        val screen = FakeScreen(
            screen(nodes = arrayOf(node("a", text = "Daily Sales", resourceId = "com.example:id/daily"))),
        )
        val skill = skill(listOf(clickStep(id = "one"), clickStep(id = "two")))
        val recorder = Recorder(cancelAfter = 0)

        val outcome = executor(screen, ScriptedProvider()).execute(skill, task(skill), recorder)

        assertThat(outcome.status).isEqualTo(OutcomeStatus.CANCELLED)
        assertThat(screen.clicked).hasSize(1)
    }

    @Test
    fun `launching an app needs no on-screen target`() = runTest {
        val screen = FakeScreen(screen(packageName = "com.example.business"))
        val launch = SkillStep(
            id = "launch",
            intent = StepIntent.LAUNCH_APP,
            target = TargetSemantics("business app"),
            action = ActionSpec.LaunchApp("com.example.business"),
            validation = ValidationSpec(mode = ValidationMode.NONE),
        )
        val skill = skill(listOf(launch))

        val outcome = executor(screen, ScriptedProvider()).execute(skill, task(skill), Recorder())

        assertThat(outcome.status).isEqualTo(OutcomeStatus.SUCCESS)
        assertThat(screen.launched).containsExactly("com.example.business")
    }

    @Test
    fun `structural validation failure is reported against the observed screen`() = runTest {
        val screen = FakeScreen(
            screen(nodes = arrayOf(node("a", text = "Daily Sales", resourceId = "com.example:id/daily"))),
        )
        val step = clickStep(
            validation = ValidationSpec(mode = ValidationMode.STRUCTURAL),
            expected = ExpectedState(requiredTexts = listOf("Yesterday")),
        )
        val skill = skill(listOf(step))
        val recorder = Recorder()

        val outcome = executor(screen, ScriptedProvider()).execute(skill, task(skill), recorder)

        assertThat(outcome.status).isEqualTo(OutcomeStatus.PARTIAL)
        assertThat(recorder.typesOf(ExecutionEventType.VALIDATION_RESULT).any { it.success == false }).isTrue()
    }
}
