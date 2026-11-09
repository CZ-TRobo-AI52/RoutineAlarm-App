package com.cztr.routinealarm.r52.skill

data class SkillEvidence(
    val implementationReceipts: Int = 0,
    val testPasses: Int = 0,
    val benchmarkPasses: Int = 0,
    val deploymentReceipts: Int = 0,
    val externalGroundTruthChecks: Int = 0,
    val regressionFailures: Int = 0,
) {
    init {
        require(
            implementationReceipts >= 0 &&
                testPasses >= 0 &&
                benchmarkPasses >= 0 &&
                deploymentReceipts >= 0 &&
                externalGroundTruthChecks >= 0 &&
                regressionFailures >= 0,
        ) { "evidence counts must be non-negative" }
    }
}

data class SkillEvalResult(
    val verifiedCapabilityGain: Double,
    val tokenCost: Long,
    val latencyMs: Long,
    val complexityCost: Double,
    val riskCost: Double,
    val externalGroundTruthChecks: Int,
    val regressionFailures: Int,
) {
    init {
        require(tokenCost >= 0 && latencyMs >= 0) { "resource costs must be non-negative" }
        require(verifiedCapabilityGain.isFinite()) { "capability gain must be finite" }
        require(complexityCost.isFinite() && riskCost.isFinite() && complexityCost >= 0.0 && riskCost >= 0.0) {
            "costs must be finite and non-negative"
        }
        require(externalGroundTruthChecks >= 0 && regressionFailures >= 0) { "evidence counts must be non-negative" }
    }

    /** Advisory planning signal only; ground-truth counts are caller-supplied and unauthenticated. */
    fun estimatedUtility(): Double {
        if (verifiedCapabilityGain <= 0.0 || externalGroundTruthChecks <= 0 || regressionFailures > 0) return 0.0
        val burden = 1.0 + tokenCost / 1000.0 + latencyMs / 1000.0 + complexityCost + riskCost
        return verifiedCapabilityGain / burden
    }

    /** Until a trusted outcome verifier is integrated, this cannot establish verified improvement. */
    fun validatedUtility(): Double = 0.0
}

data class PromotionDecision(
    val allowed: Boolean,
    val reason: String,
)

object SkillPromotionGate {
    fun evaluate(
        from: SkillLifecycleState,
        to: SkillLifecycleState,
        evidence: SkillEvidence,
    ): PromotionDecision {
        if (to.ordinal != from.ordinal + 1) {
            return PromotionDecision(false, "non_adjacent_transition")
        }
        if (evidence.regressionFailures > 0) {
            return PromotionDecision(false, "regression_present")
        }
        // Raw counts are self-reported; they cannot authenticate independent ground truth.
        if (to == SkillLifecycleState.VERIFIED) {
            return PromotionDecision(false, "requires_authenticated_ground_truth_receipt")
        }

        val allowed = when (to) {
            SkillLifecycleState.IMPLEMENTED -> evidence.implementationReceipts > 0
            SkillLifecycleState.TESTED -> evidence.testPasses > 0
            SkillLifecycleState.BENCHMARKED -> evidence.benchmarkPasses > 0
            SkillLifecycleState.DEPLOYED -> evidence.deploymentReceipts > 0
            SkillLifecycleState.VERIFIED -> false
            SkillLifecycleState.PROPOSED -> false
        }

        return if (allowed) {
            PromotionDecision(true, "evidence_satisfied")
        } else {
            PromotionDecision(false, "missing_required_evidence")
        }
    }
}
