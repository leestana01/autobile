package com.autobile.ai.task

import com.autobile.ai.provider.ResponseSchema
import com.autobile.ai.provider.boolOr
import com.autobile.ai.provider.floatOr
import com.autobile.ai.provider.intOr
import com.autobile.ai.provider.objectList
import com.autobile.ai.provider.stringList
import com.autobile.ai.provider.stringOr

/**
 * The catalogue of questions Autobile asks a model.
 *
 * Every entry is deliberately narrow: identify one element, classify one screen, read
 * one value. Autobile never asks a model to "figure out the rest" — the application
 * owns the loop and the model answers bounded questions inside it. That is what makes
 * the same skill runnable on a small on-device model and on a large hosted one, and
 * what makes each answer cheap enough to validate before acting on it.
 */
object AiTasks {

    /**
     * The single system instruction shared by every task.
     *
     * Repeating a long preamble per call would waste a meaningful fraction of a small
     * context window, so this stays short and task prompts carry the specifics.
     */
    const val SYSTEM_INSTRUCTION: String =
        "You analyse Android user interfaces. Answer only with the requested JSON. " +
            "Never invent interface elements or values that are not present in the input. " +
            "If the input does not contain the answer, say so through the response fields " +
            "rather than guessing."

    // -- Element selection ----------------------------------------------------

    /**
     * Picks which of the listed elements matches a step's target.
     *
     * This is the workhorse of skill execution after a UI change: the cached locator
     * has stopped matching, but the meaning of the step has not, so the model is asked
     * to map that meaning onto one of the elements actually on screen.
     */
    val elementMatch = ResponseSchema(
        name = "ElementMatch",
        fieldGuide = """
            index: integer, the 0-based number of the matching element, or -1 if none match
            confidence: number between 0 and 1
            reason: short explanation, at most 15 words
        """.trimIndent(),
        example = """{"index": 3, "confidence": 0.88, "reason": "labelled Daily Sales under Reports"}""",
        parser = { json ->
            ElementMatch(
                index = json.intOr("index", -1),
                confidence = json.floatOr("confidence", 0f),
                reason = json.stringOr("reason"),
            )
        },
        validator = { match ->
            when {
                match.index < -1 -> "index out of range"
                match.confidence !in 0f..1f -> "confidence out of range"
                else -> null
            }
        },
    )

    fun elementMatchPrompt(targetDescription: String, synonyms: List<String>, renderedNodes: String): String =
        buildString {
            append("Which element performs this action?\n")
            append("Action: ").append(targetDescription).append('\n')
            if (synonyms.isNotEmpty()) {
                append("Also known as: ").append(synonyms.joinToString(", ")).append('\n')
            }
            append("\nElements:\n").append(renderedNodes)
            append("\n\nIf no element performs this action, answer with index -1.")
        }

    // -- Screen classification ------------------------------------------------

    /** Decides whether the current screen is the one a step expected to reach. */
    val screenMatch = ResponseSchema(
        name = "ScreenMatch",
        fieldGuide = """
            matches: true or false
            confidence: number between 0 and 1
            actual: short description of the screen actually shown, at most 12 words
        """.trimIndent(),
        example = """{"matches": false, "confidence": 0.91, "actual": "login screen asking for a password"}""",
        parser = { json ->
            ScreenMatch(
                matches = json.boolOr("matches"),
                confidence = json.floatOr("confidence", 0f),
                actual = json.stringOr("actual"),
            )
        },
    )

    fun screenMatchPrompt(expectation: String, screenDescription: String): String = buildString {
        append("Is the screen below the one described?\n")
        append("Expected: ").append(expectation).append("\n\n")
        append("Screen:\n").append(screenDescription)
    }

    // -- Value extraction -----------------------------------------------------

    /**
     * Reads one named field off a screen.
     *
     * The field name is part of the answer so a caller can detect the common failure
     * where a model returns a number from the wrong row.
     */
    val valueExtraction = ResponseSchema(
        name = "ValueExtraction",
        fieldGuide = """
            found: true or false
            value: the value exactly as displayed, or "" when not found
            fieldLabel: the label shown next to the value, or ""
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"found": true, "value": "2,481,000", "fieldLabel": "Net sales", "confidence": 0.93}""",
        parser = { json ->
            ExtractedValue(
                found = json.boolOr("found"),
                value = json.stringOr("value"),
                fieldLabel = json.stringOr("fieldLabel"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
        validator = { extracted ->
            if (extracted.found && extracted.value.isBlank()) "found was true but value was empty" else null
        },
    )

    fun valueExtractionPrompt(fieldName: String, qualifiers: List<String>, screenDescription: String): String =
        buildString {
            append("Read one value from this screen.\n")
            append("Field: ").append(fieldName).append('\n')
            if (qualifiers.isNotEmpty()) {
                append("It must match all of: ").append(qualifiers.joinToString(", ")).append('\n')
            }
            append("\nScreen:\n").append(screenDescription)
            append("\n\nReturn the value exactly as displayed, including separators and units.")
            append(" If the field is not on this screen, set found to false.")
        }

    // -- Natural language commands --------------------------------------------

    /** Turns a spoken or typed instruction into a goal plus its parameters. */
    val commandIntent = ResponseSchema(
        name = "CommandIntent",
        fieldGuide = """
            goal: one sentence describing the outcome the user wants
            appHint: the app the task most likely involves, or ""
            parameters: array of {name, value} pairs mentioned in the request
            referencesCurrentScreen: true when the request relies on what is on screen now
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"goal":"Save the attached PDF to the Work folder","appHint":"KakaoTalk","parameters":[{"name":"folder","value":"Work"}],"referencesCurrentScreen":false,"confidence":0.86}""",
        parser = { json ->
            CommandIntent(
                goal = json.stringOr("goal"),
                appHint = json.stringOr("appHint"),
                parameters = json.objectList("parameters").associate {
                    it.stringOr("name") to it.stringOr("value")
                }.filterKeys { it.isNotBlank() },
                referencesCurrentScreen = json.boolOr("referencesCurrentScreen"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
        validator = { intent -> if (intent.goal.isBlank()) "goal was empty" else null },
    )

    fun commandIntentPrompt(command: String, screenDescription: String?): String = buildString {
        append("Interpret this request as an automation goal.\n")
        append("Request: ").append(command).append('\n')
        if (!screenDescription.isNullOrBlank()) {
            append("\nThe user is currently looking at:\n").append(screenDescription)
            append("\nResolve any words like \"this\" or \"it\" against that screen.")
        }
    }

    // -- Demonstration understanding ------------------------------------------

    /**
     * Infers what a demonstration was *for*, rather than what it consisted of.
     *
     * The distinction matters: "tap the third PDF" breaks the next day, while "open the
     * most recent settlement PDF" keeps working.
     */
    val goalInference = ResponseSchema(
        name = "GoalInference",
        fieldGuide = """
            name: a short name for this automation, at most 4 words
            goal: one sentence describing the repeatable outcome
            summary: a plain-language description the user will be asked to confirm
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"name":"Daily Sales","goal":"Report yesterday's net sales to the #daily-sales channel","summary":"Check yesterday's net sales and post it to #daily-sales","confidence":0.82}""",
        parser = { json ->
            InferredGoal(
                name = json.stringOr("name"),
                goal = json.stringOr("goal"),
                summary = json.stringOr("summary"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
        validator = { goal -> if (goal.goal.isBlank()) "goal was empty" else null },
    )

    fun goalInferencePrompt(steps: String): String = buildString {
        append("A user demonstrated a task by performing these steps.\n")
        append("Describe the repeatable intent behind them, not the individual taps.\n\n")
        append(steps)
    }

    /** Separates the steps that carry intent from mis-taps and exploration. */
    val traceSegmentation = ResponseSchema(
        name = "TraceSegmentation",
        fieldGuide = """
            steps: array of {index, role, why} where role is one of essential, navigation, noise, observation
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"steps":[{"index":0,"role":"navigation","why":"opens the app"},{"index":1,"role":"noise","why":"went back immediately"}],"confidence":0.8}""",
        parser = { json ->
            TraceSegmentation(
                steps = json.objectList("steps").mapNotNull { entry ->
                    val index = entry.intOr("index", -1)
                    if (index < 0) return@mapNotNull null
                    SegmentedStep(
                        index = index,
                        role = StepRole.parse(entry.stringOr("role")),
                        why = entry.stringOr("why"),
                    )
                },
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun traceSegmentationPrompt(steps: String): String = buildString {
        append("Classify each recorded step of this demonstration.\n")
        append("Mark a step noise when the user corrected themselves, browsed without acting,")
        append(" or immediately undid what they had just done.\n\n")
        append(steps)
    }

    /**
     * Decides which recorded literals were incidental to the day the demonstration was
     * made, and which were deliberate choices meant to stay fixed.
     */
    val variableAnalysis = ResponseSchema(
        name = "VariableAnalysis",
        fieldGuide = """
            variables: array of {name, observedValue, meaning, relativeDays} where relativeDays is
                       an integer offset from today for dates, or 0 when it is not a date
            constants: array of {name, value, why}
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"variables":[{"name":"date","observedValue":"2026-09-10","meaning":"yesterday","relativeDays":-1}],"constants":[{"name":"channel","value":"#daily-sales","why":"the fixed reporting destination"}],"confidence":0.79}""",
        parser = { json ->
            VariableAnalysis(
                variables = json.objectList("variables").map { entry ->
                    AnalysedVariable(
                        name = entry.stringOr("name"),
                        observedValue = entry.stringOr("observedValue"),
                        meaning = entry.stringOr("meaning"),
                        relativeDays = entry.intOr("relativeDays", 0),
                    )
                }.filter { it.name.isNotBlank() },
                constants = json.objectList("constants").map { entry ->
                    AnalysedConstant(
                        name = entry.stringOr("name"),
                        value = entry.stringOr("value"),
                        why = entry.stringOr("why"),
                    )
                }.filter { it.name.isNotBlank() },
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun variableAnalysisPrompt(demonstrationDate: String, steps: String): String = buildString {
        append("This demonstration was recorded on ").append(demonstrationDate).append(".\n")
        append("Identify which recorded values would differ on a later run (variables) and")
        append(" which the user intended to keep fixed (constants).\n\n")
        append(steps)
    }

    // -- Recovery -------------------------------------------------------------

    /** Proposes the next move when a step's target can no longer be found. */
    val recoveryProposal = ResponseSchema(
        name = "RecoveryProposal",
        fieldGuide = """
            action: one of tap, scroll, back, wait, give_up
            index: element number to tap when action is tap, otherwise -1
            direction: up or down when action is scroll, otherwise ""
            reason: short explanation, at most 15 words
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"action":"tap","index":2,"direction":"","reason":"Reports likely contains the sales page","confidence":0.74}""",
        parser = { json ->
            RecoveryProposal(
                action = RecoveryAction.parse(json.stringOr("action")),
                index = json.intOr("index", -1),
                direction = json.stringOr("direction"),
                reason = json.stringOr("reason"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun recoveryProposalPrompt(
        goal: String,
        stepDescription: String,
        screenDescription: String,
        renderedNodes: String,
        alreadyTried: List<String>,
    ): String = buildString {
        append("An automation cannot find what it needs. Propose one next move.\n")
        append("Overall goal: ").append(goal).append('\n')
        append("Current step: ").append(stepDescription).append("\n\n")
        append("Screen:\n").append(screenDescription).append("\n\n")
        append("Elements:\n").append(renderedNodes)
        if (alreadyTried.isNotEmpty()) {
            append("\n\nAlready tried without success: ").append(alreadyTried.joinToString("; "))
        }
        append("\n\nPropose one move only. Answer give_up if no move is likely to help.")
    }

    // -- Notifications --------------------------------------------------------

    /** Decides whether a notification means what a trigger is waiting for. */
    val notificationMatch = ResponseSchema(
        name = "NotificationMatch",
        fieldGuide = """
            matches: true or false
            confidence: number between 0 and 1
            extracted: array of {name, value} pairs worth passing to the automation
        """.trimIndent(),
        example = """{"matches":true,"confidence":0.9,"extracted":[{"name":"orderId","value":"81234"}]}""",
        parser = { json ->
            NotificationMatch(
                matches = json.boolOr("matches"),
                confidence = json.floatOr("confidence", 0f),
                extracted = json.objectList("extracted").associate {
                    it.stringOr("name") to it.stringOr("value")
                }.filterKeys { it.isNotBlank() },
            )
        },
    )

    fun notificationMatchPrompt(condition: String, title: String, text: String, packageName: String): String =
        buildString {
            append("Does this notification satisfy the condition?\n")
            append("Condition: ").append(condition).append("\n\n")
            append("From: ").append(packageName).append('\n')
            append("Title: ").append(title).append('\n')
            append("Body: ").append(text)
        }

    // -- Validation and editing ----------------------------------------------

    /** Judges whether a step's stated outcome actually happened. */
    val outcomeCheck = ResponseSchema(
        name = "OutcomeCheck",
        fieldGuide = """
            satisfied: true or false
            confidence: number between 0 and 1
            observed: what the screen shows instead, at most 15 words
        """.trimIndent(),
        example = """{"satisfied":true,"confidence":0.88,"observed":"message appears in the channel"}""",
        parser = { json ->
            OutcomeCheck(
                satisfied = json.boolOr("satisfied"),
                confidence = json.floatOr("confidence", 0f),
                observed = json.stringOr("observed"),
            )
        },
    )

    fun outcomeCheckPrompt(expectation: String, screenDescription: String): String = buildString {
        append("Did this happen?\n")
        append("Expected outcome: ").append(expectation).append("\n\n")
        append("Screen after the action:\n").append(screenDescription)
    }

    /**
     * Interprets a plain-language edit to an existing automation.
     *
     * [meaningChanged] is the field that matters: a request that alters *what* the
     * automation does is escalated to the user for confirmation instead of being
     * applied, because those are precisely the edits a misreading would make costly.
     */
    val skillEdit = ResponseSchema(
        name = "SkillEdit",
        fieldGuide = """
            field: one of trigger_time, trigger_notification, destination, value_field, name, unknown
            newValue: the replacement value as the user stated it
            meaningChanged: true when this changes what the automation does, not just how
            summary: one sentence describing the change
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"field":"trigger_time","newValue":"08:30","meaningChanged":false,"summary":"Run at 08:30 instead of 09:00","confidence":0.9}""",
        parser = { json ->
            SkillEdit(
                field = SkillEditField.parse(json.stringOr("field")),
                newValue = json.stringOr("newValue"),
                meaningChanged = json.boolOr("meaningChanged"),
                summary = json.stringOr("summary"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun skillEditPrompt(currentSummary: String, request: String): String = buildString {
        append("The user wants to change an existing automation.\n")
        append("Current automation: ").append(currentSummary).append('\n')
        append("Requested change: ").append(request)
    }

    /** Assigns risk categories to a step so the risk engine can gate it. */
    val riskClassification = ResponseSchema(
        name = "RiskClassification",
        fieldGuide = """
            categories: array of zero or more of message_send, external_post, purchase, payment,
                        transfer, subscription, booking, cancellation, delete, permission_change,
                        account_change
            reason: short explanation, at most 12 words
            confidence: number between 0 and 1
        """.trimIndent(),
        example = """{"categories":["message_send"],"reason":"posts a message to a channel","confidence":0.9}""",
        parser = { json ->
            RiskClassification(
                categories = json.stringList("categories"),
                reason = json.stringOr("reason"),
                confidence = json.floatOr("confidence", 0f),
            )
        },
    )

    fun riskClassificationPrompt(stepDescription: String, appLabel: String): String = buildString {
        append("Classify the risk of this automated action.\n")
        append("App: ").append(appLabel).append('\n')
        append("Action: ").append(stepDescription).append('\n')
        append("Return an empty array when the action only reads or navigates.")
    }
}

// -- Result types -------------------------------------------------------------

data class ElementMatch(val index: Int, val confidence: Float, val reason: String) {
    val found: Boolean get() = index >= 0
}

data class ScreenMatch(val matches: Boolean, val confidence: Float, val actual: String)

data class ExtractedValue(
    val found: Boolean,
    val value: String,
    val fieldLabel: String,
    val confidence: Float,
)

data class CommandIntent(
    val goal: String,
    val appHint: String,
    val parameters: Map<String, String>,
    val referencesCurrentScreen: Boolean,
    val confidence: Float,
)

data class InferredGoal(
    val name: String,
    val goal: String,
    val summary: String,
    val confidence: Float,
)

data class TraceSegmentation(val steps: List<SegmentedStep>, val confidence: Float)

data class SegmentedStep(val index: Int, val role: StepRole, val why: String)

enum class StepRole {
    ESSENTIAL,
    NAVIGATION,
    NOISE,
    OBSERVATION;

    companion object {
        fun parse(value: String): StepRole = when (value.trim().lowercase()) {
            "essential" -> ESSENTIAL
            "navigation" -> NAVIGATION
            "noise" -> NOISE
            "observation" -> OBSERVATION
            // An unrecognised role must not silently delete a step from the skill.
            else -> ESSENTIAL
        }
    }
}

data class VariableAnalysis(
    val variables: List<AnalysedVariable>,
    val constants: List<AnalysedConstant>,
    val confidence: Float,
)

data class AnalysedVariable(
    val name: String,
    val observedValue: String,
    val meaning: String,
    val relativeDays: Int,
) {
    val isRelativeDate: Boolean get() = relativeDays != 0
}

data class AnalysedConstant(val name: String, val value: String, val why: String)

data class RecoveryProposal(
    val action: RecoveryAction,
    val index: Int,
    val direction: String,
    val reason: String,
    val confidence: Float,
)

enum class RecoveryAction {
    TAP,
    SCROLL,
    BACK,
    WAIT,
    GIVE_UP;

    companion object {
        fun parse(value: String): RecoveryAction = when (value.trim().lowercase()) {
            "tap", "click" -> TAP
            "scroll" -> SCROLL
            "back" -> BACK
            "wait" -> WAIT
            // Anything unrecognised stops the recovery rather than guessing an action.
            else -> GIVE_UP
        }
    }
}

data class NotificationMatch(
    val matches: Boolean,
    val confidence: Float,
    val extracted: Map<String, String>,
)

data class OutcomeCheck(val satisfied: Boolean, val confidence: Float, val observed: String)

data class SkillEdit(
    val field: SkillEditField,
    val newValue: String,
    val meaningChanged: Boolean,
    val summary: String,
    val confidence: Float,
)

enum class SkillEditField {
    TRIGGER_TIME,
    TRIGGER_NOTIFICATION,
    DESTINATION,
    VALUE_FIELD,
    NAME,
    UNKNOWN;

    companion object {
        fun parse(value: String): SkillEditField = when (value.trim().lowercase()) {
            "trigger_time" -> TRIGGER_TIME
            "trigger_notification" -> TRIGGER_NOTIFICATION
            "destination" -> DESTINATION
            "value_field" -> VALUE_FIELD
            "name" -> NAME
            else -> UNKNOWN
        }
    }
}

data class RiskClassification(
    val categories: List<String>,
    val reason: String,
    val confidence: Float,
)
