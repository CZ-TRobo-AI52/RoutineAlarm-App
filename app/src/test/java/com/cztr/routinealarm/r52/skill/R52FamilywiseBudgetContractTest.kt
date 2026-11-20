package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

/** C023: falsification of the single-hypothesis holdout threshold under multiple screening. */
class R52FamilywiseBudgetContractTest {
    private fun observation(wins: Int, losses: Int) = SkillHoldoutObservation(
        evaluationId = "r52-family-v1", datasetSha256 = "d".repeat(64),
        baselineArtifactSha256 = "b".repeat(64), wins = wins, losses = losses, ties = 0,
        tokenCost = 0, latencyMs = 0, computeMicrounits = 0,
        complexityMicrounits = 0, riskMicrounits = 0,
    )

    @Test fun singleHypothesisBoundaryPreserved() {
        val lower = SkillHoldoutConfidence.lowerBoundGain(observation(63, 37), FamilywiseEvidenceBudget(1))
        assertTrue(lower > 0.0)
        assertTrue(lower < 0.02)
    }

    @Test fun previouslyPositiveNullResultIsRejectedWithFiveHundredComparisons() {
        val candidate = observation(63, 37)
        val oldLower = SkillHoldoutConfidence.observedGain(candidate) - sqrt(2.0 * ln(20.0) / 100.0)
        val newLower = SkillHoldoutConfidence.lowerBoundGain(candidate, FamilywiseEvidenceBudget(500))
        assertTrue(oldLower > 0.0)
        assertTrue(newLower <= 0.0)
    }

    @Test fun familyBoundMonotoneAndGenuineLargeGainSurvives() {
        val weak = observation(63, 37)
        val strong = observation(90, 10)
        val sizes = listOf(1, 8, 128, 500, 1_000_000)
        val bounds = sizes.map { SkillHoldoutConfidence.lowerBoundGain(weak, FamilywiseEvidenceBudget(it)) }
        assertTrue(bounds.zipWithNext().all { (a, b) -> a >= b })
        assertTrue(SkillHoldoutConfidence.lowerBoundGain(strong, FamilywiseEvidenceBudget(500)) > 0.0)
    }

    @Test fun budgetRejectsNonPositiveOrUnboundedFamily() {
        assertTrue(runCatching { FamilywiseEvidenceBudget(0) }.isFailure)
        assertTrue(runCatching { FamilywiseEvidenceBudget(-1) }.isFailure)
        assertTrue(runCatching { FamilywiseEvidenceBudget(1_000_001) }.isFailure)
    }

    @Test fun unconfiguredEngineAlwaysFailsClosed() {
        val engine = R52SkillEngine()
        val result = engine.selectEvidenceBoundImprovements(
            skillIds = listOf("a"),
            request = EvidenceBoundSelectionRequest("r52-family-v1", "d".repeat(64), "b".repeat(64)),
            authority = makeUnusedAuthority(),
            signedIndex = SignedSkillEvidenceIndex { null },
            evidenceStore = SkillEvidenceBlobStore { null },
        )
        assertNull(result.winner)
        assertEquals("comparison_family_not_preregistered", result.reason)
        assertEquals("comparison_family_not_preregistered", result.rejected["a"])
    }

    // Need no trust root when default deny-on-missing-budget path is exercised.
    private fun makeUnusedAuthority() = PinnedEd25519EvidenceAuthority(
        expectedAudience = "r52:test", expectedSourceCommitSha = "0".repeat(40),
        issuers = emptyList(), signedEvidenceIndex = SignedSkillEvidenceIndex { null },
        evidenceStore = SkillEvidenceBlobStore { null },
    )
}
