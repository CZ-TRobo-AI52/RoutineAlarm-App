package com.cztr.routinealarm.r52.skill

class R52SkillEngine(
    private val registry: SkillRegistry = SkillRegistry(),
    // Must be configured by the trusted host; selection is denied when absent.
    private val familywiseBudget: FamilywiseEvidenceBudget? = null,
) {
    private val router = SkillRouter(registry)

    fun register(skill: SkillDescriptor): RegistrationResult = registry.register(skill)

    fun resolve(
        request: SkillRequest,
        minState: SkillLifecycleState = SkillLifecycleState.IMPLEMENTED,
    ): List<SkillCandidate> = router.rank(request, minState)

    fun compose(
        request: SkillRequest,
        maxSkills: Int = 3,
        minState: SkillLifecycleState = SkillLifecycleState.IMPLEMENTED,
    ): SkillComposition = router.compose(request, maxSkills, minState)

    fun canPromote(
        skillId: String,
        to: SkillLifecycleState,
        evidence: SkillEvidence,
    ): PromotionDecision {
        val current = registry.get(skillId)
            ?: return PromotionDecision(false, "unknown_skill")
        val eligibility = SkillPromotionGate.evaluate(current.state, to, evidence)
        if (!eligibility.allowed) return eligibility
        return PromotionDecision(false, "requires_authenticated_receipt")
    }

    fun promote(
        skillId: String,
        to: SkillLifecycleState,
        receipt: SkillReceipt,
    ): PromotionDecision = registry.promote(skillId, to, receipt)

    /** Explicit production-facing gate: no caller-supplied validated flag or estimated gain. */
    fun selectEvidenceBoundImprovements(
        skillIds: Collection<String>,
        request: EvidenceBoundSelectionRequest,
        authority: PinnedEd25519EvidenceAuthority,
        signedIndex: SignedSkillEvidenceIndex,
        evidenceStore: SkillEvidenceBlobStore,
    ): EvidenceBoundImprovementDecision = if (familywiseBudget == null) {
        EvidenceBoundImprovementDecision(
            winner = null, accepted = emptyList(),
            rejected = skillIds.distinct().associateWith { "comparison_family_not_preregistered" },
            reason = "comparison_family_not_preregistered",
        )
    } else EvidenceBoundImprovementSelector(
        registry = registry,
        authority = authority,
        signedIndex = signedIndex,
        evidenceStore = evidenceStore,
        expectedEvaluationId = request.evaluationId,
        expectedDatasetSha256 = request.datasetSha256,
        expectedBaselineArtifactSha256 = request.baselineArtifactSha256,
        familywiseBudget = familywiseBudget,
        minimumSamples = request.minimumSamples,
    ).chooseBest(skillIds)

    fun descriptor(skillId: String): SkillDescriptor? = registry.get(skillId)
}
