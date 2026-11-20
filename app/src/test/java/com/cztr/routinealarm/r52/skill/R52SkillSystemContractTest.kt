package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R52SkillSystemContractTest {
    private fun skill(
        id: String,
        version: SkillVersion = SkillVersion(1, 0, 0),
        positive: Set<String> = emptySet(),
        negative: Set<String> = emptySet(),
        capabilities: Set<String> = emptySet(),
        state: SkillLifecycleState = SkillLifecycleState.PROPOSED,
        parent: SkillVersion? = null,
    ) = SkillDescriptor(
        id = id,
        version = version,
        purpose = "test-$id",
        positiveTriggers = positive,
        negativeTriggers = negative,
        capabilities = capabilities,
        dependencies = emptySet(),
        state = state,
        provenance = SkillProvenance(
            parentVersion = parent,
            hypothesisId = "H-$id",
            evalIds = setOf("E-$id"),
            changeReason = "contract-test",
        ),
    )

    private fun fixtureRegistry() = SkillRegistry(SkillEvidenceAuthority { it.issuerId == "unit-fixture" })

    private fun active(registry: SkillRegistry, skill: SkillDescriptor) {
        assertEquals(RegistrationResult.ADDED, registry.register(skill))
        val receipt = SkillReceipt(
            "impl-${skill.id}", skill.id, skill.version, SkillFingerprint.of(skill),
            SkillLifecycleState.IMPLEMENTED, SkillReceiptKind.IMPLEMENTATION,
            "fixture://${skill.id}", "unit-fixture",
        )
        assertTrue(registry.promote(skill.id, SkillLifecycleState.IMPLEMENTED, receipt).allowed)
    }

    @Test fun staleSkillVersionCannotReplaceNewerRegisteredVersion() {
        val registry = SkillRegistry()
        assertEquals(RegistrationResult.ADDED, registry.register(skill("debug", SkillVersion(2, 0, 0))))
        assertEquals(RegistrationResult.REJECTED_STALE, registry.register(skill("debug", SkillVersion(1, 9, 9))))
        assertEquals(SkillVersion(2, 0, 0), registry.get("debug")!!.version)
    }

    @Test fun equalVersionIsIdempotentOnlyForEqualDescriptor() {
        val registry = SkillRegistry()
        val original = skill("debug")
        assertEquals(RegistrationResult.ADDED, registry.register(original))
        assertEquals(RegistrationResult.UNCHANGED, registry.register(original))
        assertEquals(
            RegistrationResult.REJECTED_CONFLICT,
            registry.register(original.copy(purpose = "different")),
        )
    }

    @Test fun provenanceSurvivesVersionReplacement() {
        val registry = SkillRegistry()
        val v1 = skill("router", SkillVersion(1, 0, 0))
        val v2 = skill("router", SkillVersion(1, 1, 0), parent = v1.version)
        registry.register(v1)
        assertEquals(RegistrationResult.UPDATED, registry.register(v2))
        assertEquals(v1.version, registry.get("router")!!.provenance.parentVersion)
        assertEquals(setOf("E-router"), registry.get("router")!!.provenance.evalIds)
    }

    @Test fun negativeTriggerVetoesOtherwiseMatchingSkill() {
        val registry = fixtureRegistry()
        active(registry, skill("android-debug", positive = setOf("debug"), negative = setOf("database"), capabilities = setOf("debugging")))
        active(registry, skill("database-debug", positive = setOf("debug", "database"), capabilities = setOf("debugging")))
        val ranked = SkillRouter(registry).rank(
            SkillRequest(tokens = setOf("debug", "database"), requiredCapabilities = setOf("debugging")),
        )
        assertEquals("database-debug", ranked.first().descriptor.id)
        assertTrue(ranked.none { it.descriptor.id == "android-debug" })
    }

    @Test fun compositionCoversRequiredCapabilitiesWithoutDuplicates() {
        val registry = fixtureRegistry()
        active(registry, skill("research", positive = setOf("investigate"), capabilities = setOf("research")))
        active(registry, skill("verify", positive = setOf("investigate"), capabilities = setOf("verification")))
        active(registry, skill("combined", capabilities = setOf("research", "verification")))
        val composition = SkillRouter(registry).compose(
            SkillRequest(tokens = setOf("investigate"), requiredCapabilities = setOf("research", "verification")),
            maxSkills = 3,
        )
        assertTrue(composition.uncoveredCapabilities.isEmpty())
        assertEquals(composition.selected.map { it.descriptor.id }.distinct(), composition.selected.map { it.descriptor.id })
        assertEquals(setOf("research", "verification"), composition.coveredCapabilities)
    }

    @Test fun lifecycleCannotJumpAcrossEvidenceStages() {
        val decision = SkillPromotionGate.evaluate(
            from = SkillLifecycleState.IMPLEMENTED,
            to = SkillLifecycleState.BENCHMARKED,
            evidence = SkillEvidence(testPasses = 5, benchmarkPasses = 2),
        )
        assertFalse(decision.allowed)
        assertEquals("non_adjacent_transition", decision.reason)
    }

    @Test fun verifiedPromotionRequiresExternalGroundTruthAndNoRegression() {
        val withoutGroundTruth = SkillPromotionGate.evaluate(
            from = SkillLifecycleState.DEPLOYED,
            to = SkillLifecycleState.VERIFIED,
            evidence = SkillEvidence(deploymentReceipts = 1, externalGroundTruthChecks = 0),
        )
        assertFalse(withoutGroundTruth.allowed)

        val withRegression = SkillPromotionGate.evaluate(
            from = SkillLifecycleState.DEPLOYED,
            to = SkillLifecycleState.VERIFIED,
            evidence = SkillEvidence(deploymentReceipts = 1, externalGroundTruthChecks = 2, regressionFailures = 1),
        )
        assertFalse(withRegression.allowed)

        val valid = SkillPromotionGate.evaluate(
            from = SkillLifecycleState.DEPLOYED,
            to = SkillLifecycleState.VERIFIED,
            evidence = SkillEvidence(deploymentReceipts = 1, externalGroundTruthChecks = 2),
        )
        assertFalse(valid.allowed)
        assertEquals("requires_authenticated_ground_truth_receipt", valid.reason)
    }

    @Test fun improvementSelectionUsesValidatedRoiNotRawGain() {
        val highGainHighCost = ImprovementCandidate(
            id = "huge",
            expectedCapabilityGain = 100.0,
            timeCost = 100.0,
            computeCost = 100.0,
            complexityDebt = 20.0,
            riskCost = 20.0,
            validated = true,
        )
        val efficient = ImprovementCandidate(
            id = "efficient",
            expectedCapabilityGain = 20.0,
            timeCost = 2.0,
            computeCost = 2.0,
            complexityDebt = 1.0,
            riskCost = 1.0,
            validated = true,
        )
        val unvalidated = ImprovementCandidate(
            id = "unvalidated",
            expectedCapabilityGain = 1000.0,
            timeCost = 1.0,
            computeCost = 1.0,
            complexityDebt = 0.0,
            riskCost = 0.0,
            validated = false,
        )
        assertEquals("efficient", RecursiveImprovementKernel.chooseBest(listOf(highGainHighCost, efficient, unvalidated))!!.id)
    }

    @Test fun recursionStopsWhenMarginalMetaGainDoesNotBeatTotalBurden() {
        assertFalse(RecursiveImprovementKernel.shouldRecurse(expectedMetaGain = 3.0, cost = 1.0, risk = 1.0, complexityDebt = 1.0))
        assertTrue(RecursiveImprovementKernel.shouldRecurse(expectedMetaGain = 3.1, cost = 1.0, risk = 1.0, complexityDebt = 1.0))
    }

    @Test fun emptyRegistryProducesNoRoute() {
        val router = SkillRouter(SkillRegistry())
        assertTrue(router.rank(SkillRequest(tokens = setOf("debug"), requiredCapabilities = setOf("debugging"))).isEmpty())
        assertNull(RecursiveImprovementKernel.chooseBest(emptyList()))
    }
}
