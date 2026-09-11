package com.autobile.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SkillStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.Condition
import com.autobile.core.model.ConditionKind
import com.autobile.core.model.Direction
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.RiskCategory
import com.autobile.core.model.RiskPolicy
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConstant
import com.autobile.core.model.SkillStep
import com.autobile.core.model.SkillVariable
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TriggerSpec
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.core.model.ValueConstraints
import com.autobile.core.model.ValueRef
import com.autobile.core.model.ValueSemantics
import com.autobile.core.model.ValueType
import com.autobile.core.model.VariableBinding
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that a taught automation can still be read back on a real device.
 *
 * Skills are stored as JSON and decoded through generated serializers that are reached
 * reflectively, so shrinking can remove exactly what is needed to read them while the
 * build still succeeds and every JVM unit test still passes. The failure would surface
 * for the first time on a user's phone, after an update, as every automation they had
 * taught silently disappearing.
 *
 * Run against the shrunk build with:
 * `./gradlew connectedAndroidTest -PautobileTestBuildType=release`
 */
@RunWith(AndroidJUnit4::class)
class StoredSkillSurvivesShrinkingTest {

    private lateinit var database: AutobileDatabase
    private lateinit var store: SkillStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(AutobileDatabase.DATABASE_NAME)
        database = AutobileDatabase(context)
        store = SkillStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        InstrumentationRegistry.getInstrumentation().targetContext
            .deleteDatabase(AutobileDatabase.DATABASE_NAME)
    }

    /**
     * Exercises every polymorphic hierarchy in one document.
     *
     * Each sealed family is resolved by serial name rather than by a direct reference,
     * so any of them can be dropped independently.
     */
    private fun fullyPopulatedSkill() = SemanticSkill(
        id = "skill-round-trip",
        version = 4,
        name = "Daily sales report",
        goal = "Report yesterday's net sales to the team channel",
        description = "Compiled from a demonstration",
        trigger = TriggerSpec.Time(hour = 9, minute = 0, daysOfWeek = setOf(1, 2, 3, 4, 5)),
        variables = listOf(
            SkillVariable("date", ValueType.DATE, VariableBinding.RelativeDate(-1), "yesterday", "2026-09-10"),
            SkillVariable("netSales", ValueType.CURRENCY, VariableBinding.Extracted("read")),
            SkillVariable("orderId", ValueType.TEXT, VariableBinding.TriggerPayload("notification.title")),
            SkillVariable("query", ValueType.TEXT, VariableBinding.UserInput("What to search")),
        ),
        constants = listOf(SkillConstant("channel", "#daily-sales", "fixed destination", "destination")),
        preconditions = listOf(
            Condition("the business app is installed", ConditionKind.STRUCTURAL, "com.example.business"),
        ),
        steps = listOf(
            SkillStep(
                id = "launch",
                intent = StepIntent.LAUNCH_APP,
                target = TargetSemantics("business app"),
                action = ActionSpec.LaunchApp("com.example.business", "com.example.business.MainActivity"),
            ),
            SkillStep(
                id = "scroll",
                intent = StepIntent.SCROLL_TO,
                target = TargetSemantics("reports list"),
                action = ActionSpec.Scroll(Direction.DOWN, maxScrolls = 5),
            ),
            SkillStep(
                id = "open",
                intent = StepIntent.NAVIGATE,
                target = TargetSemantics(
                    intentLabel = "Daily Sales",
                    description = "the daily sales report",
                    synonyms = listOf("일별 매출"),
                    locators = listOf(
                        Locator(LocatorKind.RESOURCE_ID, "com.example.business:id/daily"),
                        Locator(LocatorKind.TEXT, "Daily Sales", strength = 0.7f),
                        Locator(LocatorKind.HIERARCHY_PATH, "0-2-1", strength = 0.3f),
                    ),
                ),
                action = ActionSpec.Click,
            ),
            SkillStep(
                id = "hold",
                intent = StepIntent.SELECT_ITEM,
                target = TargetSemantics("row"),
                action = ActionSpec.LongPress(800),
            ),
            SkillStep(
                id = "read",
                intent = StepIntent.READ_VALUE,
                target = TargetSemantics(
                    intentLabel = "Net sales",
                    valueSemantics = ValueSemantics("net sales", ValueType.CURRENCY, "KRW", listOf("yesterday")),
                ),
                action = ActionSpec.ReadValue("netSales"),
                validation = ValidationSpec(
                    mode = ValidationMode.VALUE,
                    valueConstraints = ValueConstraints(
                        fieldName = "net sales",
                        expectedType = ValueType.CURRENCY,
                        dateBinding = VariableBinding.RelativeDate(-1),
                        minValue = 0.0,
                    ),
                    goalCritical = true,
                ),
            ),
            SkillStep(
                id = "type",
                intent = StepIntent.ENTER_TEXT,
                target = TargetSemantics("message box"),
                action = ActionSpec.InputText(ValueRef.Template("{date} net sales: {netSales}")),
            ),
            SkillStep(
                id = "pick",
                intent = StepIntent.SELECT_ITEM,
                target = TargetSemantics("channel"),
                action = ActionSpec.InputText(ValueRef.Constant("channel")),
            ),
            SkillStep(
                id = "swipe",
                intent = StepIntent.NAVIGATE,
                target = TargetSemantics("pager"),
                action = ActionSpec.Swipe(Direction.LEFT, 0.7f, 250),
            ),
            SkillStep(id = "tap", intent = StepIntent.CONFIRM, target = TargetSemantics("ok"), action = ActionSpec.Tap(0.5f, 0.9f)),
            SkillStep(id = "wait", intent = StepIntent.WAIT, target = TargetSemantics("settle"), action = ActionSpec.Wait(1200)),
            SkillStep(id = "back", intent = StepIntent.GO_BACK, target = TargetSemantics("back"), action = ActionSpec.Back),
            SkillStep(id = "home", intent = StepIntent.GO_HOME, target = TargetSemantics("home"), action = ActionSpec.Home),
            SkillStep(
                id = "send",
                intent = StepIntent.SEND,
                target = TargetSemantics("Send"),
                action = ActionSpec.Click,
                validation = ValidationSpec(mode = ValidationMode.SEMANTIC, expectation = "the message appears", goalCritical = true),
            ),
        ),
        postconditions = listOf(Condition("the report was posted", ConditionKind.SEMANTIC)),
        riskPolicy = RiskPolicy(setOf(RiskCategory.MESSAGE_SEND), requireConfirmation = true),
        history = listOf(SkillVersionRecord(4, 1_700_000_000_000, PatchAuthor.SELF_HEAL, "Relocated the menu")),
        createdAt = 1_699_000_000_000,
        updatedAt = 1_700_000_000_000,
    )

    @Test
    fun aStoredSkillReadsBackIdentically() = runBlocking {
        val original = fullyPopulatedSkill()
        store.save(original)

        val restored = store.get(original.id)

        assertNotNull("The stored automation could not be read back", restored)
        assertEquals(original.steps.size, restored!!.steps.size)
        assertEquals(original.trigger, restored.trigger)
        assertEquals(original.variables, restored.variables)
        assertEquals(original.constants, restored.constants)
        assertEquals(original.riskPolicy, restored.riskPolicy)
        assertEquals(original.postconditions, restored.postconditions)
        original.steps.forEachIndexed { index, step ->
            assertEquals("step $index action", step.action, restored.steps[index].action)
            assertEquals("step $index target", step.target, restored.steps[index].target)
            assertEquals("step $index validation", step.validation, restored.steps[index].validation)
        }
    }

    @Test
    fun everyActionTypeKeepsItsConcreteClass() = runBlocking {
        store.save(fullyPopulatedSkill())
        val restored = store.get("skill-round-trip")!!

        assertTrue(restored.step("launch")!!.action is ActionSpec.LaunchApp)
        assertTrue(restored.step("scroll")!!.action is ActionSpec.Scroll)
        assertTrue(restored.step("open")!!.action is ActionSpec.Click)
        assertTrue(restored.step("hold")!!.action is ActionSpec.LongPress)
        assertTrue(restored.step("read")!!.action is ActionSpec.ReadValue)
        assertTrue(restored.step("type")!!.action is ActionSpec.InputText)
        assertTrue(restored.step("swipe")!!.action is ActionSpec.Swipe)
        assertTrue(restored.step("tap")!!.action is ActionSpec.Tap)
        assertTrue(restored.step("wait")!!.action is ActionSpec.Wait)
        assertTrue(restored.step("back")!!.action is ActionSpec.Back)
        assertTrue(restored.step("home")!!.action is ActionSpec.Home)
    }

    @Test
    fun everyVariableBindingKeepsItsConcreteClass() = runBlocking {
        store.save(fullyPopulatedSkill())
        val restored = store.get("skill-round-trip")!!

        val bindings = restored.variables.associate { it.name to it.binding }
        assertTrue(bindings["date"] is VariableBinding.RelativeDate)
        assertTrue(bindings["netSales"] is VariableBinding.Extracted)
        assertTrue(bindings["orderId"] is VariableBinding.TriggerPayload)
        assertTrue(bindings["query"] is VariableBinding.UserInput)
        assertEquals(-1, (bindings["date"] as VariableBinding.RelativeDate).offsetDays)
    }

    @Test
    fun supersededVersionsRemainReadable() = runBlocking {
        val original = fullyPopulatedSkill()
        store.save(original)
        store.save(original.copy(version = 5, name = "Renamed"))

        val versions = store.versions(original.id)

        assertTrue("No archived version was readable", versions.isNotEmpty())
        assertEquals(4, versions.first().version)
        assertEquals(original.steps.size, versions.first().steps.size)
    }
}
