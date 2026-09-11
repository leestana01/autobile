package com.autobile.runtime.edit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.ai.task.SkillEdit
import com.autobile.ai.task.SkillEditField
import com.autobile.core.data.AutobileDatabase
import com.autobile.core.data.SkillStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConstant
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TriggerSpec
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.core.model.ValueConstraints
import com.autobile.core.model.ValueSemantics
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.routerWith
import com.autobile.runtime.trigger.TriggerScheduler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillEditorTest {
    private lateinit var editor: SkillEditor
    private lateinit var store: SkillStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = SkillStore(AutobileDatabase(context))
        editor = SkillEditor(
            router = routerWith(ScriptedProvider()),
            skillStore = store,
            scheduler = TriggerScheduler(context),
        )
    }

    @Test
    fun `obvious schedule edits do not require an inference provider`() = runTest {
        store.save(skill())

        val preview = editor.preview("skill", "9시 말고 8시 30분", localOnly = true)

        assertThat(preview).isInstanceOf(SkillEditPreview.Ready::class.java)
        assertThat((preview as SkillEditPreview.Ready).updated.trigger).isEqualTo(TriggerSpec.Time(8, 30))
    }

    @Test
    fun `time edits preserve the selected weekdays`() {
        val skill = skill().copy(trigger = TriggerSpec.Time(9, 0, setOf(1, 3, 5)))

        val preview = editor.buildPreview(
            skill,
            edit(SkillEditField.TRIGGER_TIME, "08:30"),
        ) as SkillEditPreview.Ready

        assertThat(preview.updated.trigger).isEqualTo(TriggerSpec.Time(8, 30, setOf(1, 3, 5)))
        assertThat(preview.updated.version).isEqualTo(skill.version + 1)
    }

    @Test
    fun `natural Korean time values are accepted`() {
        val preview = editor.buildPreview(
            skill(),
            edit(SkillEditField.TRIGGER_TIME, "8시 30분"),
        ) as SkillEditPreview.Ready

        assertThat(preview.updated.trigger).isEqualTo(TriggerSpec.Time(8, 30))
    }

    @Test
    fun `invalid times are rejected instead of being scheduled`() {
        val preview = editor.buildPreview(
            skill(),
            edit(SkillEditField.TRIGGER_TIME, "29:75"),
        )

        assertThat(preview).isInstanceOf(SkillEditPreview.Rejected::class.java)
    }

    @Test
    fun `a destination edit does not overwrite an unrelated constant`() {
        val original = skill().copy(
            constants = listOf(SkillConstant("format", "PDF", semanticRole = "file type")),
        )

        val preview = editor.buildPreview(
            original,
            edit(SkillEditField.DESTINATION, "#operations", meaningChanged = true),
        ) as SkillEditPreview.Ready

        assertThat(preview.updated.constants).contains(SkillConstant("format", "PDF", semanticRole = "file type"))
        assertThat(preview.updated.constants.map { it.value }).contains("#operations")
        assertThat(preview.meaningChanged).isTrue()
    }

    @Test
    fun `a destination edit updates send semantics and drops stale text locators`() {
        val send = SkillStep(
            id = "send",
            intent = StepIntent.SEND,
            target = TargetSemantics(
                intentLabel = "#sales",
                locators = listOf(
                    Locator(LocatorKind.TEXT, "#sales"),
                    Locator(LocatorKind.RESOURCE_ID, "chat:id/room"),
                ),
            ),
            action = ActionSpec.Click,
        )
        val original = skill().copy(
            constants = listOf(SkillConstant("channel", "#sales", semanticRole = "destination")),
            steps = listOf(send),
        )

        val preview = editor.buildPreview(
            original,
            edit(SkillEditField.DESTINATION, "#operations"),
        ) as SkillEditPreview.Ready

        assertThat(preview.updated.constants.single().value).isEqualTo("#operations")
        assertThat(preview.updated.steps.single().target.intentLabel).isEqualTo("#operations")
        assertThat(preview.updated.steps.single().target.locators.map { it.kind })
            .containsExactly(LocatorKind.RESOURCE_ID)
        assertThat(preview.changedStepIds).containsExactly("send")
    }

    @Test
    fun `a value-field edit updates extraction and validation together`() {
        val read = SkillStep(
            id = "read",
            intent = StepIntent.READ_VALUE,
            target = TargetSemantics(
                intentLabel = "Gross sales",
                valueSemantics = ValueSemantics("Gross sales"),
            ),
            action = ActionSpec.ReadValue("sales"),
            validation = ValidationSpec(
                mode = ValidationMode.VALUE,
                valueConstraints = ValueConstraints("Gross sales"),
            ),
        )

        val preview = editor.buildPreview(
            skill().copy(steps = listOf(read)),
            edit(SkillEditField.VALUE_FIELD, "Net sales", meaningChanged = true),
        ) as SkillEditPreview.Ready

        val updated = preview.updated.steps.single()
        assertThat(updated.target.valueSemantics?.fieldName).isEqualTo("Net sales")
        assertThat(updated.validation.valueConstraints?.fieldName).isEqualTo("Net sales")
    }

    @Test
    fun `unsupported edits fail closed`() {
        val preview = editor.buildPreview(skill(), edit(SkillEditField.UNKNOWN, "anything"))

        assertThat(preview).isInstanceOf(SkillEditPreview.Rejected::class.java)
    }

    @Test
    fun `a correction can be previewed against a draft that is not yet saved`() = runTest {
        val draft = skill().copy(
            id = "unsaved-draft",
            steps = listOf(
                SkillStep(
                    id = "read",
                    intent = StepIntent.READ_VALUE,
                    target = TargetSemantics(
                        intentLabel = "Gross sales",
                        description = "Gross sales",
                        valueSemantics = ValueSemantics("gross sales"),
                    ),
                    action = ActionSpec.ReadValue("value"),
                    validation = ValidationSpec(
                        mode = ValidationMode.VALUE,
                        valueConstraints = ValueConstraints(fieldName = "gross sales"),
                    ),
                ),
            ),
        )

        val preview = editor.buildPreview(draft, edit(SkillEditField.VALUE_FIELD, "net sales", meaningChanged = true))

        assertThat(preview).isInstanceOf(SkillEditPreview.Ready::class.java)
        val updated = (preview as SkillEditPreview.Ready).updated
        assertThat(updated.steps.first().target.valueSemantics?.fieldName).isEqualTo("net sales")
        assertThat(updated.steps.first().validation.valueConstraints?.fieldName).isEqualTo("net sales")
        assertThat(preview.meaningChanged).isTrue()
    }

    @Test
    fun `a draft preview never consults the store`() = runTest {
        // The draft is deliberately absent from the store; a store lookup would reject it.
        val preview = editor.preview(
            skill = skill().copy(id = "not-in-store"),
            request = "9시 말고 8시 30분",
            localOnly = true,
        )

        assertThat(preview).isInstanceOf(SkillEditPreview.Ready::class.java)
        assertThat((preview as SkillEditPreview.Ready).updated.trigger).isEqualTo(TriggerSpec.Time(8, 30))
    }

    private fun skill() = SemanticSkill(
        id = "skill",
        version = 3,
        name = "Daily sales",
        goal = "Report yesterday's sales",
    )

    private fun edit(field: SkillEditField, value: String, meaningChanged: Boolean = false) = SkillEdit(
        field = field,
        newValue = value,
        meaningChanged = meaningChanged,
        summary = "Change ${field.name.lowercase()}",
        confidence = 0.9f,
    )
}
