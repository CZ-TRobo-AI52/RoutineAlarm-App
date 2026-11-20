package com.cztr.routinealarm.r52.skill

enum class RegistrationResult {
    ADDED,
    UPDATED,
    UNCHANGED,
    REJECTED_STALE,
    REJECTED_CONFLICT,
    REJECTED_UNVERIFIED_STATE,
    REJECTED_LINEAGE,
}

/**
 * In-memory registry, not a durable authority. A higher state can only be reached through an
 * authenticated, content-bound receipt supplied by the host evidence authority.
 */
class SkillRegistry(
    private val evidenceAuthority: SkillEvidenceAuthority = DenyAllSkillEvidence,
    private val verifiedAdmissionPolicy: VerifiedAdmissionPolicy = MissingVerifiedAdmissionPolicy,
) {
    private val skills = linkedMapOf<String, SkillDescriptor>()
    private val acceptedReceipts = linkedMapOf<String, SkillReceipt>()

    @Synchronized
    fun register(skill: SkillDescriptor): RegistrationResult {
        if (skill.state != SkillLifecycleState.PROPOSED) return RegistrationResult.REJECTED_UNVERIFIED_STATE
        val current = skills[skill.id]
        if (current == null) {
            if (skill.provenance.parentVersion != null) return RegistrationResult.REJECTED_LINEAGE
            skills[skill.id] = skill
            return RegistrationResult.ADDED
        }

        return when {
            skill.version < current.version -> RegistrationResult.REJECTED_STALE
            skill.version == current.version && skill == current -> RegistrationResult.UNCHANGED
            skill.version == current.version -> RegistrationResult.REJECTED_CONFLICT
            skill.provenance.parentVersion != current.version -> RegistrationResult.REJECTED_LINEAGE
            else -> {
                skills[skill.id] = skill
                RegistrationResult.UPDATED
            }
        }
    }

    @Synchronized
    fun promote(id: String, to: SkillLifecycleState, receipt: SkillReceipt): PromotionDecision {
        val current = skills[id] ?: return PromotionDecision(false, "unknown_skill")
        val priorReceipt = acceptedReceipts[receipt.receiptId]
        if (priorReceipt != null) {
            return when {
                priorReceipt != receipt -> PromotionDecision(false, "receipt_id_conflict")
                priorReceipt.skillId == id && current.version == receipt.version && current.state == to ->
                    PromotionDecision(true, "idempotent_replay")
                else -> PromotionDecision(false, "stale_receipt_replay")
            }
        }
        if (to.ordinal != current.state.ordinal + 1) {
            return PromotionDecision(false, "non_adjacent_transition")
        }
        if (receipt.skillId != id || receipt.version != current.version ||
            receipt.artifactSha256 != SkillFingerprint.of(current) || receipt.targetState != to
        ) {
            return PromotionDecision(false, "receipt_binding_mismatch")
        }
        if (receipt.kind != requiredReceiptKind(to)) {
            return PromotionDecision(false, "wrong_evidence_kind")
        }
        if (receipt.regressionFailures > 0) {
            return PromotionDecision(false, "regression_present")
        }
        val authenticated = try {
            evidenceAuthority.authenticate(receipt)
        } catch (_: Exception) {
            false // Network, parsing or authority failures never promote a skill.
        }
        if (!authenticated) return PromotionDecision(false, "receipt_not_authenticated")
        if (to == SkillLifecycleState.VERIFIED) {
            val decision = try {
                verifiedAdmissionPolicy.admit(receipt)
            } catch (_: Exception) {
                PromotionDecision(false, "verified_admission_failure")
            }
            if (!decision.allowed) return decision
        }
        skills[id] = current.copy(state = to)
        acceptedReceipts[receipt.receiptId] = receipt
        return PromotionDecision(true, "authenticated_transition")
    }

    /** Return only the receipt actually accepted for the current VERIFIED skill/version. */
    @Synchronized
    fun verifiedReceiptFor(id: String): SkillReceipt? {
        val current = skills[id]?.takeIf { it.state == SkillLifecycleState.VERIFIED } ?: return null
        val fingerprint = SkillFingerprint.of(current)
        return acceptedReceipts.values.firstOrNull { receipt ->
            receipt.skillId == current.id && receipt.version == current.version &&
                receipt.artifactSha256 == fingerprint &&
                receipt.targetState == SkillLifecycleState.VERIFIED &&
                receipt.kind == SkillReceiptKind.EXTERNAL_GROUND_TRUTH
        }
    }

    @Synchronized
    fun get(id: String): SkillDescriptor? = skills[id]

    @Synchronized
    fun all(): List<SkillDescriptor> = skills.values.toList()
}
