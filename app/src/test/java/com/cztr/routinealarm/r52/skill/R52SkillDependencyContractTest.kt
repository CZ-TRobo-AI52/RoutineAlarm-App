package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevent a skill from being routed when its execution prerequisites cannot be honored. */
class R52SkillDependencyContractTest {
    private val authority = SkillEvidenceAuthority { it.issuerId == "test-authority" }
    private fun skill(
        id: String,
        capabilities: Set<String> = emptySet(),
        positive: Set<String> = emptySet(),
        negative: Set<String> = emptySet(),
        dependencies: Set<String> = emptySet(),
    ) = SkillDescriptor(
        id, SkillVersion(1, 0, 0), "skill-$id", positive, negative,
        capabilities, dependencies, SkillLifecycleState.PROPOSED,
        SkillProvenance(null, "hyp-$id", setOf("eval-$id"), "test"),
    )
    private fun registerActive(r: SkillRegistry, s: SkillDescriptor) {
        assertEquals(RegistrationResult.ADDED, r.register(s))
        val receipt = SkillReceipt(
            "impl-${s.id}", s.id, s.version, SkillFingerprint.of(s),
            SkillLifecycleState.IMPLEMENTED, SkillReceiptKind.IMPLEMENTATION,
            "test://${s.id}", "test-authority",
        )
        assertTrue(r.promote(s.id, SkillLifecycleState.IMPLEMENTED, receipt).allowed)
    }
    private val request = SkillRequest(setOf("task"), setOf("result"))

    @Test fun missingDependencyCannotRoute() {
        val r = SkillRegistry(authority)
        registerActive(r, skill("task", setOf("result"), setOf("task"), dependencies = setOf("missing")))
        assertTrue(SkillRouter(r).rank(request).isEmpty())
        assertTrue(SkillRouter(r).compose(request).selected.isEmpty())
    }

    @Test fun dependencyBelowMinimumStateCannotRoute() {
        val r = SkillRegistry(authority)
        assertEquals(RegistrationResult.ADDED, r.register(skill("prereq")))
        registerActive(r, skill("task", setOf("result"), setOf("task"), dependencies = setOf("prereq")))
        assertTrue(SkillRouter(r).rank(request).isEmpty())
    }

    @Test fun excludedDependencyBlocksEntirePlan() {
        val r = SkillRegistry(authority)
        registerActive(r, skill("prereq"))
        registerActive(r, skill("task", setOf("result"), setOf("task"), dependencies = setOf("prereq")))
        val req = request.copy(excludedSkillIds = setOf("prereq"))
        assertTrue(SkillRouter(r).rank(req).isEmpty())
        assertEquals(setOf("result"), SkillRouter(r).compose(req).uncoveredCapabilities)
    }

    @Test fun indirectCycleFailsClosed() {
        val r = SkillRegistry(authority)
        registerActive(r, skill("task", setOf("result"), setOf("task"), dependencies = setOf("a")))
        registerActive(r, skill("a", dependencies = setOf("b")))
        registerActive(r, skill("b", dependencies = setOf("a")))
        assertTrue(SkillRouter(r).rank(request).isEmpty())
    }

    @Test fun negativeTriggerOnDependencyBlocksParent() {
        val r = SkillRegistry(authority)
        registerActive(r, skill("prereq", negative = setOf("task")))
        registerActive(r, skill("task", setOf("result"), setOf("task"), dependencies = setOf("prereq")))
        assertTrue(SkillRouter(r).rank(request).isEmpty())
    }

    @Test fun compositionSchedulesTransitivePrerequisitesFirst() {
        val r = SkillRegistry(authority)
        registerActive(r, skill("first"))
        registerActive(r, skill("second", dependencies = setOf("first")))
        registerActive(r, skill("target", setOf("result"), setOf("task"), dependencies = setOf("second")))
        val composition = SkillRouter(r).compose(request, maxSkills = 3)
        assertEquals(listOf("first", "second", "target"), composition.selected.map { it.descriptor.id })
        assertEquals(setOf("result"), composition.coveredCapabilities)
        assertTrue(composition.uncoveredCapabilities.isEmpty())
    }

    @Test fun insufficientSkillBudgetRefusesIncompleteDependencyBundle() {
        val r = SkillRegistry(authority)
        registerActive(r, skill("first"))
        registerActive(r, skill("target", setOf("result"), setOf("task"), dependencies = setOf("first")))
        val composition = SkillRouter(r).compose(request, maxSkills = 1)
        assertTrue(composition.selected.isEmpty())
        assertEquals(setOf("result"), composition.uncoveredCapabilities)
    }

    @Test fun compositionPrefersSameCoverageWithFewerDependencies() {
        val r = SkillRegistry(authority)
        registerActive(r, skill("pre"))
        registerActive(r, skill("direct", setOf("result")))
        registerActive(r, skill("wrapped", setOf("result"), setOf("task"), dependencies = setOf("pre")))
        val composition = SkillRouter(r).compose(request, maxSkills = 2)
        assertEquals(listOf("direct"), composition.selected.map { it.descriptor.id })
        assertFalse(composition.uncoveredCapabilities.isNotEmpty())
    }
}
