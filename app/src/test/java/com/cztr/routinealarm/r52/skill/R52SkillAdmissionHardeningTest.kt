package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R52SkillAdmissionHardeningTest {
    private fun skill(id: String = "debug") = SkillDescriptor(
        id = id, version = SkillVersion(1, 0, 0), purpose = "diagnose",
        positiveTriggers = setOf("diagnose"), negativeTriggers = emptySet(),
        capabilities = setOf("debugging"), dependencies = emptySet(),
        state = SkillLifecycleState.PROPOSED,
        provenance = SkillProvenance(null, "H-1", setOf("E-1"), "first revision"),
    )

    private fun receipt(
        skill: SkillDescriptor,
        state: SkillLifecycleState = SkillLifecycleState.IMPLEMENTED,
        id: String = "receipt-1",
        kind: SkillReceiptKind = SkillReceiptKind.IMPLEMENTATION,
    ) = SkillReceipt(
        receiptId = id, skillId = skill.id, version = skill.version,
        artifactSha256 = SkillFingerprint.of(skill), targetState = state, kind = kind,
        evidenceRef = "test-fixture://evidence", issuerId = "fixture",
    )

    @Test fun activeStatesCannotBeInjectedThroughRegistration() {
        val registry = SkillRegistry()
        for (state in SkillLifecycleState.values().filter { it != SkillLifecycleState.PROPOSED }) {
            assertEquals(RegistrationResult.REJECTED_UNVERIFIED_STATE, registry.register(skill().copy(state = state)))
        }
        assertTrue(registry.all().isEmpty())
    }

    @Test fun versionChangesNeedExactParentAndMustStartProposed() {
        val registry = SkillRegistry()
        val original = skill()
        assertEquals(RegistrationResult.ADDED, registry.register(original))
        val newer = original.copy(version = SkillVersion(1, 1, 0))
        assertEquals(RegistrationResult.REJECTED_LINEAGE, registry.register(newer))
        assertEquals(
            RegistrationResult.UPDATED,
            registry.register(newer.copy(provenance = newer.provenance.copy(parentVersion = original.version))),
        )
        assertEquals(SkillLifecycleState.PROPOSED, registry.get(original.id)!!.state)
    }

    @Test fun aSubmittedReceiptDoesNotAuthorizeItself() {
        val registry = SkillRegistry()
        val candidate = skill()
        registry.register(candidate)
        assertEquals(
            "receipt_not_authenticated",
            registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, receipt(candidate)).reason,
        )
        assertEquals(SkillLifecycleState.PROPOSED, registry.get(candidate.id)!!.state)
    }

    @Test fun receiptIsBoundToExactContentVersionStageAndKind() {
        val registry = SkillRegistry(SkillEvidenceAuthority { it.issuerId == "fixture" })
        val candidate = skill()
        registry.register(candidate)
        val correct = receipt(candidate)
        assertFalse(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, correct.copy(artifactSha256 = "0".repeat(64))).allowed)
        assertFalse(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, correct.copy(version = SkillVersion(2, 0, 0))).allowed)
        assertFalse(registry.promote(candidate.id, SkillLifecycleState.TESTED, correct.copy(targetState = SkillLifecycleState.TESTED)).allowed)
        assertFalse(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, correct.copy(kind = SkillReceiptKind.BENCHMARK_PASS)).allowed)
        assertTrue(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, correct).allowed)
        assertEquals(SkillLifecycleState.IMPLEMENTED, registry.get(candidate.id)!!.state)
    }

    @Test fun acceptedReceiptIsIdempotentButIdCollisionIsRejected() {
        val registry = SkillRegistry(SkillEvidenceAuthority { true })
        val candidate = skill()
        registry.register(candidate)
        val first = receipt(candidate)
        assertTrue(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, first).allowed)
        assertEquals("idempotent_replay", registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, first).reason)
        assertEquals("receipt_id_conflict", registry.promote(candidate.id, SkillLifecycleState.TESTED,
            first.copy(targetState = SkillLifecycleState.TESTED, kind = SkillReceiptKind.TEST_PASS)).reason)
    }

    @Test fun hostAuthorityFailureNeverPromotes() {
        val registry = SkillRegistry(SkillEvidenceAuthority { throw IllegalStateException("unavailable") })
        val candidate = skill()
        registry.register(candidate)
        assertFalse(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, receipt(candidate)).allowed)
        assertEquals(SkillLifecycleState.PROPOSED, registry.get(candidate.id)!!.state)
    }

    @Test fun externalVerificationNeedsExactEvidenceKindAndAuthenticatedAuthority() {
        val registry = SkillRegistry(SkillEvidenceAuthority { it.issuerId == "fixture" },
            VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        val candidate = skill()
        registry.register(candidate)
        val stages = listOf(
            SkillLifecycleState.IMPLEMENTED to SkillReceiptKind.IMPLEMENTATION,
            SkillLifecycleState.TESTED to SkillReceiptKind.TEST_PASS,
            SkillLifecycleState.BENCHMARKED to SkillReceiptKind.BENCHMARK_PASS,
            SkillLifecycleState.DEPLOYED to SkillReceiptKind.DEPLOYMENT,
        )
        stages.forEachIndexed { index, (state, kind) ->
            assertTrue(registry.promote(candidate.id, state, receipt(candidate, state, "e-$index", kind)).allowed)
        }
        val wrongType = receipt(candidate, SkillLifecycleState.VERIFIED, "e-verified", SkillReceiptKind.TEST_PASS)
        assertFalse(registry.promote(candidate.id, SkillLifecycleState.VERIFIED, wrongType).allowed)
        assertEquals(SkillLifecycleState.DEPLOYED, registry.get(candidate.id)!!.state)
        // Fixture-only simulation. This does not establish a real external ground truth authority.
        assertTrue(registry.promote(candidate.id, SkillLifecycleState.VERIFIED,
            wrongType.copy(kind = SkillReceiptKind.EXTERNAL_GROUND_TRUTH)).allowed)
    }

    @Test fun nonFiniteCandidateInputsAreRejectedInsteadOfOptimized() {
        assertTrue(runCatching { ImprovementCandidate("bad", Double.NaN, 1.0, 1.0, 0.0, 0.0, true) }.isFailure)
        assertTrue(runCatching { ImprovementCandidate("bad", 1.0, 0.0, Double.POSITIVE_INFINITY, 0.0, 0.0, true) }.isFailure)
        assertTrue(runCatching { RecursiveImprovementKernel.shouldRecurse(Double.NaN, 0.0, 0.0, 0.0) }.isFailure)
        assertTrue(runCatching { SkillEvalResult(Double.POSITIVE_INFINITY, 1, 1, 0.0, 0.0, 1, 0) }.isFailure)
    }

    @Test fun callerReportedGroundTruthCannotInventValidatedUtility() {
        val fabricated = SkillEvalResult(10.0, 100, 100, 1.0, 1.0, 100, 0)
        assertEquals(0.0, fabricated.validatedUtility(), 0.0)
        assertTrue(fabricated.estimatedUtility() > 0.0)
    }

    @Test fun unauthenticatedSkillsCannotAppearInDefaultRoutes() {
        val registry = SkillRegistry()
        assertEquals(RegistrationResult.ADDED, registry.register(skill()))
        assertTrue(SkillRouter(registry).rank(SkillRequest(setOf("diagnose"), setOf("debugging"))).isEmpty())
    }
}
