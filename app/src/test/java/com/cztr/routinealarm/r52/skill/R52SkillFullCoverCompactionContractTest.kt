package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fail-first counterexamples for needlessly large *complete* plans in C011. */
class R52SkillFullCoverCompactionContractTest {
    private val authority = SkillEvidenceAuthority { it.issuerId == "test-authority" }

    private fun add(registry: SkillRegistry, id: String, caps: Set<String>, deps: Set<String> = emptySet()) {
        val descriptor = SkillDescriptor(
            id, SkillVersion(1, 0, 0), "skill-$id", setOf("task"), emptySet(),
            caps, deps, SkillLifecycleState.PROPOSED,
            SkillProvenance(null, "hyp-$id", setOf("eval-$id"), "test"),
        )
        assertEquals(RegistrationResult.ADDED, registry.register(descriptor))
        val receipt = SkillReceipt(
            "impl-$id", id, descriptor.version, SkillFingerprint.of(descriptor),
            SkillLifecycleState.IMPLEMENTED, SkillReceiptKind.IMPLEMENTATION,
            "test://$id", "test-authority",
        )
        assertTrue(registry.promote(id, SkillLifecycleState.IMPLEMENTED, receipt).allowed)
    }

    @Test fun completeGreedyThreeMustShrinkToTwo() {
        val registry = SkillRegistry(authority)
        add(registry, "A", setOf("a", "b", "c", "d"))
        add(registry, "X", setOf("a", "b", "e"))
        add(registry, "Y", setOf("c", "d", "f"))
        val plan = SkillRouter(registry).compose(
            SkillRequest(setOf("task"), setOf("a", "b", "c", "d", "e", "f")), maxSkills = 3,
        )
        assertEquals(emptySet<String>(), plan.uncoveredCapabilities)
        assertEquals(setOf("X", "Y"), plan.selected.map { it.descriptor.id }.toSet())
        assertEquals(2, plan.selected.size)
    }

    @Test fun compactionCountsTransitiveDependenciesAndKeepsTopologicalOrder() {
        val registry = SkillRegistry(authority)
        add(registry, "P", emptySet())
        add(registry, "A", setOf("a", "b", "c", "d"))
        add(registry, "X", setOf("a", "b", "e"), setOf("P"))
        add(registry, "Y", setOf("c", "d", "f"))
        val plan = SkillRouter(registry).compose(
            SkillRequest(setOf("task"), setOf("a", "b", "c", "d", "e", "f")), maxSkills = 4,
        )
        assertEquals(emptySet<String>(), plan.uncoveredCapabilities)
        assertEquals(setOf("P", "X", "Y"), plan.selected.map { it.descriptor.id }.toSet())
        assertTrue(plan.selected.indexOfFirst { it.descriptor.id == "P" } <
            plan.selected.indexOfFirst { it.descriptor.id == "X" })
    }

    @Test fun excludesUnsafeShortcutsWhenCompactPlanExists() {
        val registry = SkillRegistry(authority)
        add(registry, "A", setOf("a", "b", "c", "d"))
        add(registry, "X", setOf("a", "b", "e"))
        add(registry, "Y", setOf("c", "d", "f"))
        add(registry, "Z", setOf("a", "b", "c", "d", "e", "f"))
        val req = SkillRequest(
            setOf("task"), setOf("a", "b", "c", "d", "e", "f"),
            excludedSkillIds = setOf("Z"),
        )
        val plan = SkillRouter(registry).compose(req, maxSkills = 3)
        assertEquals(emptySet<String>(), plan.uncoveredCapabilities)
        assertEquals(setOf("X", "Y"), plan.selected.map { it.descriptor.id }.toSet())
    }
}
