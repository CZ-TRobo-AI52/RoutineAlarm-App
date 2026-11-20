package com.cztr.routinealarm.r52.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases independently falsified by C014 exhaustive subset oracle against the C013 router. */
class R52SkillTripleCompactionContractTest {
    private val authority = SkillEvidenceAuthority { it.issuerId == "c014-contracts" }
    private fun add(registry: SkillRegistry,id: String,caps:Set<String>, deps:Set<String> = emptySet(),
                    proposed:Boolean=false, negative:Boolean=false) {
        val descriptor = SkillDescriptor(id,SkillVersion(1,0,0),"C014 regression",setOf("task"),
            if (negative) setOf("task") else emptySet(),caps,deps,SkillLifecycleState.PROPOSED,
            SkillProvenance(null,"C014-$id",emptySet(),"independent counterexample"))
        assertEquals(RegistrationResult.ADDED,registry.register(descriptor))
        if (!proposed) {
            val r = SkillReceipt("C014-$id",id,descriptor.version,SkillFingerprint.of(descriptor),
                SkillLifecycleState.IMPLEMENTED,SkillReceiptKind.IMPLEMENTATION,"test://C014/$id","c014-contracts")
            assertTrue(registry.promote(id,SkillLifecycleState.IMPLEMENTED,r).allowed)
        }
    }
    private fun checkThree(registry:SkillRegistry,caps:Set<String>,exclude:Set<String> = emptySet()) {
        val plan = SkillRouter(registry).compose(SkillRequest(setOf("task"),caps,exclude),maxSkills=4)
        assertTrue(plan.uncoveredCapabilities.isEmpty())
        assertEquals(3,plan.selected.size)
    }
    @Test fun completeFourSkillGreedyCompactsToThree() {
        val r=SkillRegistry(authority)
        add(r,"s0",setOf("c5"));add(r,"s1",setOf("c0","c1","c2","c3"))
        add(r,"s2",emptySet());add(r,"s3",setOf("c0","c2","c3"))
        add(r,"s4",setOf("c2","c5"),setOf("s2"),negative=true)
        add(r,"s5",setOf("c1","c2","c4"))
        add(r,"s6",setOf("c0","c3","c4","c6"),setOf("s3"))
        add(r,"s7",setOf("c2","c4","c6"),setOf("s1","s3"))
        add(r,"s8",setOf("c0","c1","c3","c4","c6"))
        checkThree(r,(0..6).map { "c$it" }.toSet())
    }
    @Test fun threeSkillPlanMustRespectUnavailableDependenciesAndExclusions() {
        val r=SkillRegistry(authority)
        add(r,"s0",setOf("c1","c2","c5"))
        add(r,"s1",setOf("c0","c1","c3","c6"))
        add(r,"s2",setOf("c3","c4"))
        add(r,"s3",setOf("c0","c3","c6"),setOf("s2"),proposed=true)
        add(r,"s4",setOf("c1","c2","c3","c6"),setOf("s2"))
        add(r,"s5",setOf("c2","c4","c6"),proposed=true,negative=true)
        add(r,"s6",setOf("c2","c3","c4"),setOf("s3"))
        add(r,"s7",setOf("c1","c3","c5","c6"))
        checkThree(r,(0..6).map { "c$it" }.toSet(),exclude=setOf("s7"))
    }
}
