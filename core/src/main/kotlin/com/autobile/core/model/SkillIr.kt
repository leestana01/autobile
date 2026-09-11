package com.autobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Semantic Skill intermediate representation: a reusable, executable description
 * of something the user demonstrated or described.
 *
 * The IR is deliberately model-neutral. Nothing in this file names an inference
 * provider, a prompt format or a model family, so a skill compiled on one device can
 * be executed by any runtime tier — on-device, local or cloud — without translation
 * and without tying the user's automations to a single AI vendor.
 */
@Serializable
data class SemanticSkill(
    val id: String,
    val version: Int,
    val name: String,
    /** The user-meaningful outcome, not the click sequence that happened to achieve it. */
    val goal: String,
    val description: String = "",
    val trigger: TriggerSpec = TriggerSpec.Manual,
    val inputs: List<SkillInput> = emptyList(),
    val variables: List<SkillVariable> = emptyList(),
    val constants: List<SkillConstant> = emptyList(),
    val preconditions: List<Condition> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val postconditions: List<Condition> = emptyList(),
    val riskPolicy: RiskPolicy = RiskPolicy(),
    val autonomyLevel: AutonomyLevel = AutonomyLevel.L2_ASK_BEFORE_ACTION,
    val confidence: SkillConfidence = SkillConfidence(),
    val runtimeRequirements: RuntimeRequirements = RuntimeRequirements(),
    val history: List<SkillVersionRecord> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /**
      * The autonomy the runtime may actually use, after both the risk policy and the
      * learned confidence band have had their say.
      *
      * The declared [autonomyLevel] is an upper bound, never a guarantee: a skill that
      * keeps failing loses autonomy automatically, and the risk policy can cap a
      * trusted skill back down to explicit confirmation.
      */
    fun effectiveAutonomy(): AutonomyLevel {
        val riskCapped = minOf(autonomyLevel, riskPolicy.maxAutonomy)
        return when (confidence.band) {
            ConfidenceBand.HIGH -> riskCapped
            ConfidenceBand.MEDIUM -> riskCapped
            ConfidenceBand.LOW -> minOf(riskCapped, AutonomyLevel.L2_ASK_BEFORE_ACTION)
            ConfidenceBand.DEGRADED -> AutonomyLevel.L0_OBSERVE
        }
    }

    fun step(id: String): SkillStep? = steps.firstOrNull { it.id == id }
}

@Serializable
data class SkillStep(
    val id: String,
    /** What this step is for, independent of how it is performed. */
    val intent: StepIntent,
    val target: TargetSemantics,
    val preferredResolver: ResolverKind = ResolverKind.ACCESSIBILITY_NODE,
    val action: ActionSpec,
    val expectedState: ExpectedState = ExpectedState(),
    val validation: ValidationSpec = ValidationSpec(),
    val fallback: FallbackPolicy = FallbackPolicy(),
    val optional: Boolean = false,
    val description: String = "",
)

enum class StepIntent {
    LAUNCH_APP,
    NAVIGATE,
    SELECT_ITEM,
    OPEN_TARGET,
    READ_VALUE,
    ENTER_TEXT,
    SET_OPTION,
    SCROLL_TO,
    CONFIRM,
    SEND,
    SHARE,
    SAVE,
    DELETE,
    GO_BACK,
    GO_HOME,
    WAIT,
}

/**
 * Semantic description of what a step acts on.
 *
 * A step targets meaning ("the daily sales menu"), not a screen position. [locators]
 * are cached hints that let the fast path find the element with no inference at all;
 * they are hints only. When they stop matching after an app update, [intentLabel],
 * [description] and [synonyms] are what a reasoning tier uses to locate the element
 * again, which is what lets a skill survive UI changes.
 */
@Serializable
data class TargetSemantics(
    val intentLabel: String,
    val description: String = "",
    val synonyms: List<String> = emptyList(),
    val locators: List<Locator> = emptyList(),
    val screen: ScreenSemantics? = null,
    val valueSemantics: ValueSemantics? = null,
) {
    fun matchTerms(): List<String> =
        (listOf(intentLabel, description) + synonyms + locators.mapNotNull { it.textualValue() })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

@Serializable
data class Locator(
    val kind: LocatorKind,
    val value: String,
    val packageName: String? = null,
    /** Prior strength in [0,1]; resource ids are strong, raw bounds are weak. */
    val strength: Float = 1f,
) {
    fun textualValue(): String? = when (kind) {
        LocatorKind.TEXT, LocatorKind.CONTENT_DESCRIPTION -> value
        else -> null
    }

    val isDeterministic: Boolean
        get() = kind == LocatorKind.RESOURCE_ID || kind == LocatorKind.HIERARCHY_PATH
}

enum class LocatorKind {
    RESOURCE_ID,
    TEXT,
    CONTENT_DESCRIPTION,
    CLASS_NAME,
    HIERARCHY_PATH,
    BOUNDS_HINT,
}

/** Which perception/actuation strategy a step prefers, before any fallback. */
enum class ResolverKind {
    DIRECT_API,
    ACCESSIBILITY_NODE,
    GESTURE,
    VISION,
}

@Serializable
data class ScreenSemantics(
    val label: String,
    val description: String = "",
    val packageName: String? = null,
)

/** What a READ_VALUE step is actually reading — the basis for semantic validation. */
@Serializable
data class ValueSemantics(
    val fieldName: String,
    val valueType: ValueType = ValueType.TEXT,
    val unit: String? = null,
    val qualifiers: List<String> = emptyList(),
)

enum class ValueType { TEXT, NUMBER, CURRENCY, DATE, PERCENT, BOOLEAN }

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

@Serializable
sealed interface ActionSpec {
    @Serializable
    @SerialName("click")
    data object Click : ActionSpec

    @Serializable
    @SerialName("long_press")
    data class LongPress(val durationMs: Long = 600) : ActionSpec

    /** Coordinate tap, expressed as a ratio of screen size. Fallback only: prefer [Click]. */
    @Serializable
    @SerialName("tap")
    data class Tap(val xRatio: Float, val yRatio: Float) : ActionSpec

    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val direction: Direction,
        val distanceRatio: Float = 0.6f,
        val durationMs: Long = 300,
    ) : ActionSpec

    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val direction: Direction = Direction.DOWN,
        val maxScrolls: Int = 8,
    ) : ActionSpec

    @Serializable
    @SerialName("input_text")
    data class InputText(
        val value: ValueRef,
        val clearExisting: Boolean = true,
        val submit: Boolean = false,
    ) : ActionSpec

    @Serializable
    @SerialName("back")
    data object Back : ActionSpec

    @Serializable
    @SerialName("home")
    data object Home : ActionSpec

    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(val packageName: String, val activity: String? = null) : ActionSpec

    /** Reads a value into a variable; performs no screen mutation. */
    @Serializable
    @SerialName("read_value")
    data class ReadValue(val outputVariable: String) : ActionSpec

    @Serializable
    @SerialName("wait")
    data class Wait(val millis: Long = 1000) : ActionSpec
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

@Serializable
sealed interface ValueRef {
    @Serializable
    @SerialName("literal")
    data class Literal(val value: String) : ValueRef

    @Serializable
    @SerialName("variable")
    data class Variable(val name: String) : ValueRef

    @Serializable
    @SerialName("constant")
    data class Constant(val name: String) : ValueRef

    /** Deferred template, e.g. "yesterday net sales: {netSales}". */
    @Serializable
    @SerialName("template")
    data class Template(val template: String) : ValueRef
}

// ---------------------------------------------------------------------------
// Inputs, variables and constants
// ---------------------------------------------------------------------------

@Serializable
data class SkillInput(
    val name: String,
    val type: ValueType = ValueType.TEXT,
    val prompt: String = "",
    val required: Boolean = true,
    val defaultValue: String? = null,
)

/**
 * A value that legitimately changes between runs.
 *
 * Recognising these is what separates a compiled skill from a replayed recording: the
 * compiler's job is to notice that the `2026-09-10` the user typed during the
 * demonstration actually meant "yesterday", and store a [VariableBinding.RelativeDate]
 * rather than the literal date.
 */
@Serializable
data class SkillVariable(
    val name: String,
    val type: ValueType = ValueType.TEXT,
    val binding: VariableBinding = VariableBinding.Extracted(),
    val description: String = "",
    val exampleValue: String? = null,
)

@Serializable
sealed interface VariableBinding {
    @Serializable
    @SerialName("relative_date")
    data class RelativeDate(
        val offsetDays: Int = -1,
        val pattern: String = "yyyy-MM-dd",
    ) : VariableBinding

    @Serializable
    @SerialName("user_input")
    data class UserInput(val prompt: String = "") : VariableBinding

    /** Produced at run time by a [StepIntent.READ_VALUE] step. */
    @Serializable
    @SerialName("extracted")
    data class Extracted(val sourceStepId: String? = null) : VariableBinding

    @Serializable
    @SerialName("trigger_payload")
    data class TriggerPayload(val field: String) : VariableBinding
}

/**
 * A value the user intended to stay fixed across runs, such as the destination channel
 * of a recurring report. The mirror image of [SkillVariable].
 */
@Serializable
data class SkillConstant(
    val name: String,
    val value: String,
    val description: String = "",
    val semanticRole: String = "",
)

// ---------------------------------------------------------------------------
// Conditions, expected state and validation
// ---------------------------------------------------------------------------

@Serializable
data class Condition(
    val description: String,
    val kind: ConditionKind = ConditionKind.SEMANTIC,
    val packageName: String? = null,
    val requiredTexts: List<String> = emptyList(),
    val forbiddenTexts: List<String> = emptyList(),
)

enum class ConditionKind { STRUCTURAL, SEMANTIC, VALUE }

@Serializable
data class ExpectedState(
    val screen: ScreenSemantics? = null,
    val requiredPackage: String? = null,
    val requiredTexts: List<String> = emptyList(),
    val forbiddenTexts: List<String> = emptyList(),
    val description: String = "",
) {
    val isEmpty: Boolean
        get() = screen == null && requiredPackage == null &&
            requiredTexts.isEmpty() && forbiddenTexts.isEmpty() && description.isBlank()
}

@Serializable
data class ValidationSpec(
    val mode: ValidationMode = ValidationMode.STRUCTURAL,
    val timeoutMs: Long = 5_000,
    val settleMs: Long = 350,
    /** Natural-language post-condition, used when [mode] is [ValidationMode.SEMANTIC]. */
    val expectation: String = "",
    val valueConstraints: ValueConstraints? = null,
    /** Marks a step whose success is required before the task may be reported as complete. */
    val goalCritical: Boolean = false,
)

enum class ValidationMode { NONE, STRUCTURAL, SEMANTIC, VALUE }

/**
 * Constraints that make value validation contextual rather than a bare equality check.
 *
 * Reading "2,481,000" is not enough; the field it came from and the date it applies to
 * have to agree as well, otherwise a skill can confidently report last week's gross
 * figure as today's net one.
 */
@Serializable
data class ValueConstraints(
    val fieldName: String,
    val expectedType: ValueType = ValueType.TEXT,
    val dateBinding: VariableBinding? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val currency: String? = null,
    val regex: String? = null,
    val nonEmpty: Boolean = true,
)

@Serializable
data class FallbackPolicy(
    val allowSemanticSearch: Boolean = true,
    val allowDeviceAi: Boolean = true,
    val allowLocalLlm: Boolean = true,
    val allowCloudAi: Boolean = true,
    val allowVision: Boolean = true,
    val maxRetries: Int = 2,
    val onFailure: FailureAction = FailureAction.RECOVER,
)

enum class FailureAction { RECOVER, ASK_USER, SKIP, ABORT }

// ---------------------------------------------------------------------------
// Risk, autonomy and confidence
// ---------------------------------------------------------------------------

/**
 * How much freedom the agent has to act on the user's behalf.
 *
 * Declared in ascending order of freedom so that `minOf` over several opinions
 * naturally yields the most restrictive one.
 */
enum class AutonomyLevel {
    L0_OBSERVE,
    L1_SUGGEST,
    L2_ASK_BEFORE_ACTION,
    L3_AUTONOMOUS_LOW_RISK,
    L4_EXPLICITLY_TRUSTED;

    val label: String
        get() = when (this) {
            L0_OBSERVE -> "Observe"
            L1_SUGGEST -> "Suggest"
            L2_ASK_BEFORE_ACTION -> "Ask before action"
            L3_AUTONOMOUS_LOW_RISK -> "Autonomous (low risk)"
            L4_EXPLICITLY_TRUSTED -> "Trusted"
        }
}

enum class RiskCategory {
    MESSAGE_SEND,
    EXTERNAL_POST,
    PURCHASE,
    PAYMENT,
    TRANSFER,
    SUBSCRIPTION,
    BOOKING,
    CANCELLATION,
    DELETE,
    PERMISSION_CHANGE,
    ACCOUNT_CHANGE;

    companion object {
        /**
         * Every category listed here is irreversible or externally visible, so all of
         * them require confirmation unless the user has explicitly trusted the skill.
         */
        val highImpact: Set<RiskCategory> = entries.toSet()
    }
}

@Serializable
data class RiskPolicy(
    val categories: Set<RiskCategory> = emptySet(),
    val requireConfirmation: Boolean = true,
    val maxAutonomy: AutonomyLevel = AutonomyLevel.L4_EXPLICITLY_TRUSTED,
) {
    val isHighImpact: Boolean get() = categories.isNotEmpty()
}

@Serializable
data class SkillConfidence(
    val score: Float = 0.5f,
    val executionCount: Int = 0,
    val successCount: Int = 0,
    val validationFailures: Int = 0,
    val recoveryCount: Int = 0,
    val userCorrectionCount: Int = 0,
    val lastUiMatchScore: Float = 0f,
    val uiDriftEvents: Int = 0,
    val semanticAmbiguity: Float = 0f,
) {
    val successRate: Float
        get() = if (executionCount == 0) 0f else successCount.toFloat() / executionCount

    /**
     * Coarse health band used for autonomy decisions.
     *
     * The thresholds below are deliberate first-pass values chosen to fail safe, and
     * are expected to be re-tuned once real execution data is available.
     */
    val band: ConfidenceBand
        get() = when {
            score < 0.25f || (executionCount >= 3 && successRate < 0.4f) -> ConfidenceBand.DEGRADED
            score < 0.5f -> ConfidenceBand.LOW
            score < 0.8f -> ConfidenceBand.MEDIUM
            else -> ConfidenceBand.HIGH
        }
}

enum class ConfidenceBand { HIGH, MEDIUM, LOW, DEGRADED }

@Serializable
data class RuntimeRequirements(
    val requiresScreenshot: Boolean = false,
    val requiresDeviceAi: Boolean = false,
    val requiresCloud: Boolean = false,
    val requiresNetwork: Boolean = false,
    val requiresUnlockedDevice: Boolean = true,
    val requiredPackages: List<String> = emptyList(),
    val minApiLevel: Int = 30,
)

// ---------------------------------------------------------------------------
// Triggers
// ---------------------------------------------------------------------------

@Serializable
sealed interface TriggerSpec {
    @Serializable
    @SerialName("manual")
    data object Manual : TriggerSpec

    @Serializable
    @SerialName("time")
    data class Time(
        val hour: Int,
        val minute: Int,
        /** 1 = Monday … 7 = Sunday (ISO). Empty means every day. */
        val daysOfWeek: Set<Int> = emptySet(),
    ) : TriggerSpec {
        fun describe(): String {
            val time = "%02d:%02d".format(hour, minute)
            if (daysOfWeek.isEmpty() || daysOfWeek.size == 7) return "Every day $time"
            val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            return daysOfWeek.sorted().joinToString(",") { names[it - 1] } + " $time"
        }
    }

    @Serializable
    @SerialName("notification")
    data class Notification(
        val packageName: String? = null,
        val titleContains: String? = null,
        val textContains: String? = null,
        val category: String? = null,
        /**
         * Natural-language meaning check, for example "this notification means a
         * delivery completed". Classified on-device wherever the device can do it, so
         * that enabling a notification trigger does not imply streaming every
         * notification off the phone.
         */
        val semanticCondition: String? = null,
    ) : TriggerSpec {
        fun describe(): String = buildList {
            packageName?.let { add(it.substringAfterLast('.')) }
            semanticCondition?.let { add(it) }
            titleContains?.let { add("title~$it") }
            textContains?.let { add("text~$it") }
        }.joinToString(" · ").ifEmpty { "Any notification" }
    }
}

// ---------------------------------------------------------------------------
// Versioning
// ---------------------------------------------------------------------------

@Serializable
data class SkillVersionRecord(
    val version: Int,
    val createdAt: Long,
    val author: PatchAuthor,
    val reason: String,
    val summary: String = "",
    val changedStepIds: List<String> = emptyList(),
)

enum class PatchAuthor { USER, SELF_HEAL, COMPILER }
