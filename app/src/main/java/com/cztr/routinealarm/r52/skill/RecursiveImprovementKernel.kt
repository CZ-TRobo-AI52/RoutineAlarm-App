package com.cztr.routinealarm.r52.skill

data class ImprovementCandidate(
    val id: String,
    val expectedCapabilityGain: Double,
    val timeCost: Double,
    val computeCost: Double,
    val complexityDebt: Double,
    val riskCost: Double,
    val validated: Boolean,
) {
    init {
        require(id.isNotBlank()) { "candidate id must not be blank" }
        require(expectedCapabilityGain.isFinite()) { "candidate gain must be finite" }
        require(listOf(timeCost, computeCost, complexityDebt, riskCost).all { it.isFinite() && it >= 0.0 }) {
            "candidate costs must be finite and non-negative"
        }
    }

    fun roi(): Double {
        if (!validated || expectedCapabilityGain <= 0.0) return 0.0
        val burden = 1.0 + timeCost + computeCost + complexityDebt + riskCost
        return expectedCapabilityGain / burden
    }
}

data class ImprovementDecision(
    val candidate: ImprovementCandidate?,
    val reason: String,
)

object RecursiveImprovementKernel {
    fun chooseBest(candidates: Collection<ImprovementCandidate>): ImprovementCandidate? =
        candidates
            .asSequence()
            .filter { it.validated && it.expectedCapabilityGain > 0.0 }
            .maxWithOrNull(compareBy<ImprovementCandidate> { it.roi() }.thenBy { it.expectedCapabilityGain }.thenByDescending { it.id })

    fun shouldRecurse(
        expectedMetaGain: Double,
        cost: Double,
        risk: Double,
        complexityDebt: Double,
    ): Boolean {
        require(expectedMetaGain.isFinite()) { "meta gain must be finite" }
        require(listOf(cost, risk, complexityDebt).all { it.isFinite() && it >= 0.0 }) {
            "recursion burdens must be finite and non-negative"
        }
        return expectedMetaGain > cost + risk + complexityDebt
    }

    fun decide(candidates: Collection<ImprovementCandidate>): ImprovementDecision {
        val best = chooseBest(candidates)
        return if (best == null) {
            ImprovementDecision(null, "no_validated_positive_gain_candidate")
        } else {
            ImprovementDecision(best, "highest_validated_roi")
        }
    }
}
