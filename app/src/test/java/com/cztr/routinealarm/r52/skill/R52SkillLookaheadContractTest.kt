package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent counterexamples for greedy-only skill composition. */
class R52SkillLookaheadContractTest {
    private val authority = SkillEvidenceAuthority { it.issuerId == "test-authority" }
    private fun activate(registry: SkillRegistry, id: String, caps: Set<String>, deps: Set<String> = emptySet()) {
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

    private val goal = SkillRequest(setOf("task"), setOf("a", "b", "c", "d", "e", "f"))

    @Test fun lookaheadBeatsGreedyHighGainTrap() {
        val registry = SkillRegistry(authority)
        activate(registry, "A", setOf("a", "b", "c", "d"))
        activate(registry, "B", setOf("a", "b", "e"))
        activate(registry, "C", setOf("c", "d", "f"))
        val plan = SkillRouter(registry).compose(goal, maxSkills = 2)
        assertTrue(plan.uncoveredCapabilities.isEmpty())
        assertEquals(setOf("B", "C"), plan.selected.map { it.descriptor.id }.toSet())
    }

    @Test fun smallRandomCompositionsMatchIndependentExactOracle() {
        val random = kotlin.random.Random(20261104)
        val caps = listOf("a", "b", "c", "d", "e", "f")
        repeat(80) { trial ->
            val registry = SkillRegistry(authority)
            val choices = (0 until 9).map { n ->
                caps.filter { random.nextInt(5) < 2 }.toSet().also { set ->
                    activate(registry, "trial${trial}_$n", set)
                }
            }
            val exact = buildList {
                add(0)
                for (i in choices.indices) {
                    add(choices[i].size)
                    for (j in i + 1 until choices.size) {
                        add((choices[i] + choices[j]).size)
                    }
                }
            }.max()
            val planned = SkillRouter(registry).compose(goal, maxSkills = 2)
            assertEquals(exact, planned.coveredCapabilities.size)
            assertTrue(planned.selected.size <= 2)
        }
    }

    @Test fun lookaheadRespectsTransitiveDependencyBudget() {
        val registry = SkillRegistry(authority)
        activate(registry, "P", emptySet())
        activate(registry, "A", setOf("a", "b", "c", "d"), setOf("P"))
        activate(registry, "B", setOf("a", "b", "e"))
        activate(registry, "C", setOf("c", "d", "f"))
        val plan = SkillRouter(registry).compose(goal, maxSkills = 3)
        assertTrue(plan.uncoveredCapabilities.isEmpty())
        assertEquals(setOf("B", "C"), plan.selected.map { it.descriptor.id }.toSet())
        assertEquals(2, plan.selected.size)
    }
}
