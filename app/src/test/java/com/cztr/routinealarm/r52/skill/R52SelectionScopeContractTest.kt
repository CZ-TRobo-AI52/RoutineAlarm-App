package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C086: preregistered selection scope must be deterministic and fail closed on mutation. */
class R52SelectionScopeContractTest {
    private val evaluation = "holdout-c086"
    private val dataset = "d".repeat(64)
    private val baseline = "b".repeat(64)
    private val commit = "a".repeat(40)

    @Test fun candidateSetFingerprintIsOrderAndDuplicateInvariant() {
        val a = PreregisteredSelectionScope.canonicalCandidateSetSha256(listOf("skill-b", "skill-a", "skill-a"))
        val b = PreregisteredSelectionScope.canonicalCandidateSetSha256(listOf(" skill-a ", "skill-b"))
        assertEquals(a, b)
    }

    @Test fun candidateSetSubstitutionChangesFingerprint() {
        val a = PreregisteredSelectionScope.canonicalCandidateSetSha256(listOf("skill-a", "skill-b"))
        val b = PreregisteredSelectionScope.canonicalCandidateSetSha256(listOf("skill-a", "skill-x"))
        assertFalse(a == b)
    }

    @Test fun createdScopeSelfValidates() {
        val scope = PreregisteredSelectionScope.create(
            scopeId = "c086-scope",
            evaluationId = evaluation,
            datasetSha256 = dataset,
            baselineArtifactSha256 = baseline,
            candidateIds = listOf("skill-a", "skill-b"),
            registeredHypotheses = 2,
            sourceCommitSha256 = commit,
        )
        assertTrue(scope.validate())
    }

    @Test fun scopeDigestMutationFailsClosed() {
        val scope = PreregisteredSelectionScope.create(
            scopeId = "c086-scope",
            evaluationId = evaluation,
            datasetSha256 = dataset,
            baselineArtifactSha256 = baseline,
            candidateIds = listOf("skill-a", "skill-b"),
            registeredHypotheses = 2,
            sourceCommitSha256 = commit,
        )
        assertFalse(scope.copy(datasetSha256 = "e".repeat(64)).validate())
    }

    @Test fun candidateSetAndFamilyBudgetAreBoundToSamePopulation() {
        val scope = PreregisteredSelectionScope.create(
            scopeId = "c086-scope",
            evaluationId = evaluation,
            datasetSha256 = dataset,
            baselineArtifactSha256 = baseline,
            candidateIds = listOf("skill-a", "skill-b"),
            registeredHypotheses = 2,
            sourceCommitSha256 = commit,
        )
        assertEquals(
            scope.candidateSetSha256,
            PreregisteredSelectionScope.canonicalCandidateSetSha256(listOf("skill-b", "skill-a")),
        )
        assertEquals(2, scope.registeredHypotheses)
    }
}
