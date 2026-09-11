package com.autobile.runtime.recovery

import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.ai.task.RecoveryAction
import com.autobile.core.common.Ids
import com.autobile.core.data.SkillPatchCandidate
import com.autobile.core.model.Direction
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.UiNode
import com.autobile.runtime.risk.RiskEngine

/**
 * Gets a run back on track when a step cannot find what it needs, and turns a
 * successful repair into a durable improvement.
 *
 * Apps are redesigned without warning. A recorded path that worked yesterday can be two
 * menus deeper today. Recovery reasons about the current screen and the overall goal to
 * propose one move at a time, bounded by [maxMoves] so a confused run explores briefly
 * and then stops rather than wandering through an app indefinitely.
 *
 * When a repair works, it is not thrown away: it becomes a patch candidate, so the same
 * change does not have to be rediscovered on every future run.
 */
class SelfHealingEngine(
    private val router: AiRuntimeRouter,
    private val riskEngine: RiskEngine,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
    private val maxMoves: Int = DEFAULT_MAX_MOVES,
) {

    /**
     * Proposes the next move for a stuck step.
     *
     * @param attemptedMoves moves already tried in this recovery, passed back so the
     *   reasoning tier does not propose the same failed move repeatedly.
     */
    suspend fun proposeMove(
        skill: SemanticSkill,
        step: SkillStep,
        snapshot: ScreenSnapshot,
        attemptedMoves: List<String>,
        localOnly: Boolean = false,
    ): RecoveryMove {
        if (attemptedMoves.size >= maxMoves) {
            return RecoveryMove.GiveUp("Tried $maxMoves moves without finding \"${step.target.intentLabel}\"")
        }

        val candidates = minimizer.relevantNodes(snapshot, step.target.matchTerms())
        val screenDescription = minimizer.describeScreen(snapshot)
        val prompt = AiTasks.recoveryProposalPrompt(
            goal = skill.goal,
            stepDescription = step.description.ifBlank { step.target.description.ifBlank { step.target.intentLabel } },
            screenDescription = screenDescription,
            renderedNodes = minimizer.renderNodes(candidates),
            alreadyTried = attemptedMoves,
        )

        val routed = router.infer(
            label = "recovery-proposal",
            schema = AiTasks.recoveryProposal,
            prompt = prompt,
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                // A structural change needs more than pattern matching, so recovery is
                // where escalating past the device tier is genuinely justified.
                complexity = com.autobile.core.model.TaskComplexity.COMPLEX,
                minConfidence = RECOVERY_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
                estimatedInputTokens = minimizer.estimateTokens(prompt),
            ),
        )

        val proposal = routed.value ?: return RecoveryMove.GiveUp("No runtime could propose a recovery")

        return when (proposal.action) {
            RecoveryAction.TAP -> candidates.getOrNull(proposal.index)
                ?.let {
                    RecoveryMove.Tap(
                        node = it,
                        reason = proposal.reason,
                        tier = routed.tier,
                        usedCloud = routed.usedCloud,
                        cloudWasDecisive = routed.cloudWasDecisive,
                    )
                }
                ?: RecoveryMove.GiveUp("Proposed element is not on screen")

            RecoveryAction.SCROLL -> RecoveryMove.Scroll(
                direction = if (proposal.direction.equals("up", ignoreCase = true)) Direction.UP else Direction.DOWN,
                reason = proposal.reason,
                tier = routed.tier,
                usedCloud = routed.usedCloud,
                cloudWasDecisive = routed.cloudWasDecisive,
            )

            RecoveryAction.BACK -> RecoveryMove.Back(
                reason = proposal.reason,
                tier = routed.tier,
                usedCloud = routed.usedCloud,
                cloudWasDecisive = routed.cloudWasDecisive,
            )

            RecoveryAction.WAIT -> RecoveryMove.Wait(
                millis = WAIT_MOVE_MS,
                reason = proposal.reason,
                tier = routed.tier,
                usedCloud = routed.usedCloud,
                cloudWasDecisive = routed.cloudWasDecisive,
            )

            RecoveryAction.GIVE_UP -> RecoveryMove.GiveUp(
                proposal.reason.ifBlank { "No move is likely to help" },
            )
        }
    }

    /**
     * Turns a successful repair into a proposed new skill version.
     *
     * The candidate is stored rather than applied. Two things then decide its fate: a
     * repair that only re-points a locator can be adopted automatically, while one that
     * changes what the step means is held for the user, because that is exactly the
     * class of change where a confident-but-wrong repair does lasting damage.
     */
    fun buildPatch(
        skill: SemanticSkill,
        originalStep: SkillStep,
        repairedStep: SkillStep,
        navigationSteps: List<SkillStep>,
        summary: String,
        now: Long,
    ): SkillPatchCandidate {
        val index = skill.steps.indexOfFirst { it.id == originalStep.id }
        val patchedSteps = if (index < 0) {
            skill.steps
        } else {
            buildList {
                addAll(skill.steps.subList(0, index))
                addAll(navigationSteps)
                add(repairedStep)
                addAll(skill.steps.subList(index + 1, skill.steps.size))
            }
        }

        val nextVersion = skill.version + 1
        val patched = skill.copy(
            version = nextVersion,
            steps = patchedSteps,
            history = skill.history + SkillVersionRecord(
                version = nextVersion,
                createdAt = now,
                author = PatchAuthor.SELF_HEAL,
                reason = summary,
                changedStepIds = (navigationSteps.map { it.id } + repairedStep.id),
            ),
        )

        val unsafe = riskEngine.isUnsafeMutation(originalStep, repairedStep) ||
            navigationSteps.any { riskEngine.mutatesState(it) }

        return SkillPatchCandidate(
            skillId = skill.id,
            baseVersion = skill.version,
            patchedSkill = patched,
            summary = summary,
            createdAt = now,
            requiresUserConfirmation = unsafe,
        )
    }

    /**
     * Rewrites a step to point at the element that actually worked.
     *
     * The freshly observed identifiers are put at the head of the locator list so the
     * next run takes the fast path, while the previous locators are kept behind them:
     * an app that reverts a change, or a layout that differs per device, then still
     * resolves without inference.
     */
    fun repairStep(step: SkillStep, resolvedNode: UiNode): SkillStep {
        val learned = buildList {
            resolvedNode.resourceId?.let { add(Locator(LocatorKind.RESOURCE_ID, it, strength = 1f)) }
            resolvedNode.label().takeIf { it.isNotBlank() }?.let {
                add(Locator(LocatorKind.TEXT, it, strength = 0.7f))
            }
            if (resolvedNode.indexPath.isNotEmpty()) {
                add(Locator(LocatorKind.HIERARCHY_PATH, resolvedNode.hierarchyPath(), strength = 0.4f))
            }
        }
        val retained = step.target.locators.filterNot { existing ->
            learned.any { it.kind == existing.kind && it.value == existing.value }
        }
        return step.copy(target = step.target.copy(locators = learned + retained))
    }

    /** Creates a navigation step for a move that turned out to be necessary. */
    fun navigationStepFor(node: UiNode, reason: String): SkillStep = SkillStep(
        id = Ids.step(),
        intent = com.autobile.core.model.StepIntent.NAVIGATE,
        target = com.autobile.core.model.TargetSemantics(
            intentLabel = node.label().ifBlank { "navigation" },
            description = node.label(),
            locators = buildList {
                node.resourceId?.let { add(Locator(LocatorKind.RESOURCE_ID, it)) }
                node.label().takeIf { it.isNotBlank() }?.let { add(Locator(LocatorKind.TEXT, it, strength = 0.7f)) }
            },
        ),
        action = com.autobile.core.model.ActionSpec.Click,
        description = reason,
    )

    private companion object {
        const val DEFAULT_MAX_MOVES = 4
        const val RECOVERY_CONFIDENCE_THRESHOLD = 0.5f
        const val WAIT_MOVE_MS = 1_500L
    }
}

sealed interface RecoveryMove {
    val reason: String

    data class Tap(
        val node: UiNode,
        override val reason: String,
        val tier: RuntimeTier,
        val usedCloud: Boolean,
        val cloudWasDecisive: Boolean,
    ) : RecoveryMove

    data class Scroll(
        val direction: Direction,
        override val reason: String,
        val tier: RuntimeTier,
        val usedCloud: Boolean,
        val cloudWasDecisive: Boolean,
    ) : RecoveryMove

    data class Back(
        override val reason: String,
        val tier: RuntimeTier,
        val usedCloud: Boolean,
        val cloudWasDecisive: Boolean,
    ) : RecoveryMove

    data class Wait(
        val millis: Long,
        override val reason: String,
        val tier: RuntimeTier,
        val usedCloud: Boolean,
        val cloudWasDecisive: Boolean,
    ) : RecoveryMove

    data class GiveUp(override val reason: String) : RecoveryMove
}
