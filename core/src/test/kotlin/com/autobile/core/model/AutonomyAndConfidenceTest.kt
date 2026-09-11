package com.autobile.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Confidence banding, and the way risk and confidence together cap autonomy. */
class AutonomyAndConfidenceTest {

    @Test
    fun `confidence bands widen from degraded to high as the score rises`() {
        assertThat(SkillConfidence(score = 0.95f).band).isEqualTo(ConfidenceBand.HIGH)
        assertThat(SkillConfidence(score = 0.6f).band).isEqualTo(ConfidenceBand.MEDIUM)
        assertThat(SkillConfidence(score = 0.3f).band).isEqualTo(ConfidenceBand.LOW)
        assertThat(SkillConfidence(score = 0.1f).band).isEqualTo(ConfidenceBand.DEGRADED)
    }

    @Test
    fun `repeated failures degrade a skill even when the raw score is healthy`() {
        val confidence = SkillConfidence(score = 0.7f, executionCount = 5, successCount = 1)
        assertThat(confidence.band).isEqualTo(ConfidenceBand.DEGRADED)
    }

    @Test
    fun `degraded skills lose autonomy entirely`() {
        val skill = SemanticSkill(
            id = "s", version = 1, name = "n", goal = "g",
            autonomyLevel = AutonomyLevel.L4_EXPLICITLY_TRUSTED,
            confidence = SkillConfidence(score = 0.05f),
        )
        assertThat(skill.effectiveAutonomy()).isEqualTo(AutonomyLevel.L0_OBSERVE)
    }

    @Test
    fun `risk policy caps autonomy below the skill's own level`() {
        val skill = SemanticSkill(
            id = "s", version = 1, name = "n", goal = "g",
            autonomyLevel = AutonomyLevel.L4_EXPLICITLY_TRUSTED,
            confidence = SkillConfidence(score = 0.95f),
            riskPolicy = RiskPolicy(
                categories = setOf(RiskCategory.PAYMENT),
                maxAutonomy = AutonomyLevel.L2_ASK_BEFORE_ACTION,
            ),
        )
        assertThat(skill.effectiveAutonomy()).isEqualTo(AutonomyLevel.L2_ASK_BEFORE_ACTION)
    }

    @Test
    fun `low confidence forces confirmation regardless of declared autonomy`() {
        val skill = SemanticSkill(
            id = "s", version = 1, name = "n", goal = "g",
            autonomyLevel = AutonomyLevel.L3_AUTONOMOUS_LOW_RISK,
            confidence = SkillConfidence(score = 0.35f),
        )
        assertThat(skill.effectiveAutonomy()).isEqualTo(AutonomyLevel.L2_ASK_BEFORE_ACTION)
    }
}
