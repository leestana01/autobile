package com.autobile.runtime.compiler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.ai.task.AnalysedConstant
import com.autobile.ai.task.AnalysedVariable
import com.autobile.ai.task.InferredGoal
import com.autobile.ai.task.VariableAnalysis
import com.autobile.core.data.AppPolicyStore
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SettingsStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.RiskCategory
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.StateTransition
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TraceEvent
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.VariableBinding
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.routerWith
import com.autobile.runtime.teach.TraceSegmenter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Compilation is what separates this from a macro recorder: the output has to encode
 * what the user meant, not the literal values that happened to be on screen that day.
 */
@RunWith(RobolectricTestRunner::class)
class SkillCompilerTest {

    private lateinit var riskEngine: RiskEngine
    private val today = LocalDate.of(2026, 9, 11)

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsStore(context)
        settings.setKillSwitch(false)
        riskEngine = RiskEngine(AppPolicyStore(AutobileDatabase(context)), settings)
    }

    private fun compiler(provider: ScriptedProvider) = SkillCompiler(
        router = routerWith(provider),
        segmenter = TraceSegmenter(routerWith(provider)),
        riskEngine = riskEngine,
        today = { today },
    )

    private fun event(
        index: Int,
        action: ObservedAction,
        label: String = "Item",
        resourceId: String? = null,
        typed: String? = null,
        afterWindow: String = "Reports",
        packageName: String = "com.example.business",
    ) = TraceEvent(
        id = "e$index",
        timestamp = index.toLong(),
        packageName = packageName,
        windowContext = afterWindow,
        before = ScreenSnapshot(packageName = packageName, windowTitle = "Home"),
        after = ScreenSnapshot(packageName = packageName, windowTitle = afterWindow),
        action = action,
        targetNode = node("n$index", text = label, resourceId = resourceId),
        inputValue = typed,
        stateTransition = StateTransition(packageName, packageName, "Home", afterWindow),
    )

    private fun trace(vararg events: TraceEvent) =
        DemonstrationTrace(id = "t", label = "Daily report", startedAt = 0, endedAt = 1000, events = events.toList())

    private fun provider() = ScriptedProvider()
        .answerWith(
            "goal-inference",
            InferredGoal(
                name = "Daily Sales",
                goal = "Report yesterday's net sales to #daily-sales",
                summary = "Check yesterday's net sales and post it to #daily-sales",
                confidence = 0.85f,
            ),
        )
        .answerWith(
            "variable-analysis",
            VariableAnalysis(
                variables = listOf(
                    AnalysedVariable(
                        name = "date",
                        observedValue = "2026-09-10",
                        meaning = "yesterday",
                        relativeDays = -1,
                    ),
                ),
                constants = listOf(AnalysedConstant("channel", "#daily-sales", "fixed destination")),
                confidence = 0.8f,
            ),
        )

    @Test
    fun `an empty demonstration cannot be compiled`() = runTest {
        val result = compiler(provider()).compile(trace())
        assertThat(result).isInstanceOf(CompilationResult.Failed::class.java)
    }

    @Test
    fun `the inferred goal becomes the skill's goal`() = runTest {
        val result = compiler(provider()).compile(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.Click, label = "Daily Sales"),
            ),
        ) as CompilationResult.Success

        assertThat(result.skill.goal).isEqualTo("Report yesterday's net sales to #daily-sales")
        assertThat(result.skill.name).isEqualTo("Daily Sales")
    }

    @Test
    fun `a recorded date becomes a relative binding rather than a literal`() = runTest {
        val result = compiler(provider()).compile(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.TextInput("2026-09-10"), label = "Date", typed = "2026-09-10"),
            ),
        ) as CompilationResult.Success

        val date = result.skill.variables.first { it.name == "date" }
        assertThat(date.binding).isEqualTo(VariableBinding.RelativeDate(offsetDays = -1, pattern = "yyyy-MM-dd"))
    }

    @Test
    fun `a deliberate fixed value becomes a constant`() = runTest {
        val result = compiler(provider()).compile(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.Click, label = "Send"),
            ),
        ) as CompilationResult.Success

        assertThat(result.skill.constants.map { it.value }).contains("#daily-sales")
    }

    @Test
    fun `opening an app compiles to a launch step, not a tap`() = runTest {
        val result = compiler(provider()).compile(
            trace(event(0, ObservedAction.AppOpen("com.example.business"))),
        ) as CompilationResult.Success

        val step = result.skill.steps.first()
        assertThat(step.intent).isEqualTo(StepIntent.LAUNCH_APP)
        assertThat(step.action).isInstanceOf(ActionSpec.LaunchApp::class.java)
    }

    @Test
    fun `recorded identifiers are kept as locators for the fast path`() = runTest {
        val result = compiler(provider()).compile(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.Click, label = "Daily Sales", resourceId = "com.example:id/daily"),
            ),
        ) as CompilationResult.Success

        val target = result.skill.steps.last().target
        assertThat(target.locators.map { it.kind }).contains(LocatorKind.RESOURCE_ID)
        assertThat(target.locators.first { it.kind == LocatorKind.RESOURCE_ID }.value)
            .isEqualTo("com.example:id/daily")
    }

    @Test
    fun `a send step is recognised and carries a message risk`() = runTest {
        val result = compiler(provider()).compile(
            trace(
                event(0, ObservedAction.AppOpen("com.example.chat", ), packageName = "com.example.chat"),
                event(1, ObservedAction.Click, label = "Send", packageName = "com.example.chat"),
            ),
        ) as CompilationResult.Success

        assertThat(result.skill.steps.last().intent).isEqualTo(StepIntent.SEND)
        assertThat(result.skill.riskPolicy.categories).contains(RiskCategory.MESSAGE_SEND)
    }

    @Test
    fun `a risky skill is capped below unattended autonomy`() = runTest {
        val result = compiler(provider()).compile(
            trace(
                event(0, ObservedAction.AppOpen("com.example.chat"), packageName = "com.example.chat"),
                event(1, ObservedAction.Click, label = "Send", packageName = "com.example.chat"),
            ),
        ) as CompilationResult.Success

        assertThat(result.skill.riskPolicy.maxAutonomy).isEqualTo(AutonomyLevel.L2_ASK_BEFORE_ACTION)
        assertThat(result.skill.riskPolicy.requireConfirmation).isTrue()
    }

    @Test
    fun `a new skill starts needing confirmation rather than trusted`() = runTest {
        val result = compiler(provider()).compile(
            trace(event(0, ObservedAction.AppOpen("com.example.business"))),
        ) as CompilationResult.Success

        assertThat(result.skill.autonomyLevel).isEqualTo(AutonomyLevel.L2_ASK_BEFORE_ACTION)
        assertThat(result.skill.effectiveAutonomy()).isAtMost(AutonomyLevel.L2_ASK_BEFORE_ACTION)
    }

    @Test
    fun `the final step gets a goal-critical check so partial runs are not reported as done`() = runTest {
        val result = compiler(provider()).compile(
            trace(
                event(0, ObservedAction.AppOpen("com.example.chat"), packageName = "com.example.chat"),
                event(1, ObservedAction.Click, label = "Send", packageName = "com.example.chat"),
            ),
        ) as CompilationResult.Success

        assertThat(result.skill.steps.last().validation.goalCritical).isTrue()
        assertThat(result.skill.steps.last().validation.mode).isEqualTo(ValidationMode.SEMANTIC)
        assertThat(result.skill.postconditions).isNotEmpty()
    }

    @Test
    fun `the required app becomes a precondition`() = runTest {
        val result = compiler(provider()).compile(
            trace(event(0, ObservedAction.AppOpen("com.example.business"))),
        ) as CompilationResult.Success

        assertThat(result.skill.preconditions.first().packageName).isEqualTo("com.example.business")
        assertThat(result.skill.runtimeRequirements.requiredPackages).contains("com.example.business")
    }

    @Test
    fun `compilation still succeeds when no runtime can infer a goal`() = runTest {
        val result = compiler(ScriptedProvider(available = false)).compile(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.Click, label = "Daily Sales"),
            ),
        )

        assertThat(result).isInstanceOf(CompilationResult.Success::class.java)
        val skill = (result as CompilationResult.Success).skill
        assertThat(skill.name).isNotEmpty()
        assertThat(skill.steps).isNotEmpty()
    }
}
