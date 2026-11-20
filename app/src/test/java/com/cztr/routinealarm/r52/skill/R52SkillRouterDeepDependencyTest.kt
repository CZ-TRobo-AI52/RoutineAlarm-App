package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C039: permanent protection against recursive DFS stack exhaustion and relevance bypass. */
class R52SkillRouterDeepDependencyTest {
    private val authority = SkillEvidenceAuthority { it.issuerId == "c039-unit-test" }

    private fun add(registry: SkillRegistry, id: String, dep: String?, root: Boolean, active: Boolean = true) {
        val skill = SkillDescriptor(
            id = id,
            version = SkillVersion(1, 0, 0),
            purpose = "chain-$id",
            positiveTriggers = if (root) setOf("task") else emptySet(),
            negativeTriggers = emptySet(),
            capabilities = if (root) setOf("result") else emptySet(),
            dependencies = dep?.let(::setOf) ?: emptySet(),
            state = SkillLifecycleState.PROPOSED,
            provenance = SkillProvenance(null, "hyp-$id", setOf("eval-$id"), "c039-regression"),
        )
        assertEquals(RegistrationResult.ADDED, registry.register(skill))
        if (active) {
            val receipt = SkillReceipt(
                "impl-$id", id, skill.version, SkillFingerprint.of(skill),
                SkillLifecycleState.IMPLEMENTED, SkillReceiptKind.IMPLEMENTATION,
                "test://c039/$id", "c039-unit-test",
            )
            assertTrue(registry.promote(id, SkillLifecycleState.IMPLEMENTED, receipt).allowed)
        }
    }

    @Test fun longDependencyChainCanBeRankedWithoutStackOverflow() {
        val registry = SkillRegistry(authority)
        // The last node is the only relevant root; prerequisites are deliberately unscored.
        for (i in 0 until 1200) {
            add(registry, "node-$i", if (i == 0) null else "node-${i - 1}", i == 1199)
        }
        val ranked = SkillRouter(registry).rank(SkillRequest(setOf("task"), setOf("result")))
        assertEquals(listOf("node-1199"), ranked.map { it.descriptor.id })
    }

    @Test fun scoredRootStillRejectsUnpromotedDeepPrerequisite() {
        val registry = SkillRegistry(authority)
        add(registry, "blocked", null, false, active = false)
        add(registry, "middle", "blocked", false)
        add(registry, "root", "middle", true)
        val request = SkillRequest(setOf("task"), setOf("result"))
        assertTrue(SkillRouter(registry).rank(request).isEmpty())
        assertTrue(SkillRouter(registry).compose(request).selected.isEmpty())
    }
}
