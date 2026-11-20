package com.cztr.routinealarm.r52.skill

import java.security.MessageDigest
import kotlin.math.ln
import kotlin.math.sqrt

/** A separate host-controlled policy is required to cross the VERIFIED boundary. */
fun interface VerifiedAdmissionPolicy {
    fun admit(receipt: SkillReceipt): PromotionDecision
}

internal object MissingVerifiedAdmissionPolicy : VerifiedAdmissionPolicy {
    override fun admit(receipt: SkillReceipt) = PromotionDecision(false, "verified_admission_policy_required")
}

/** Result of checking typed evidence against a host-pinned benchmark, never against caller policy. */
internal data class VerifiedHoldoutProof(
    val observation: SkillHoldoutObservation?,
    val evidenceSha256: String?,
    val reason: String,
)

/** Shared verification path for lifecycle admission AND evidence-bound improvement selection. */
internal class PinnedHoldoutProofVerifier(
    private val authority: PinnedEd25519EvidenceAuthority,
    private val signedIndex: SignedSkillEvidenceIndex,
    private val evidenceStore: SkillEvidenceBlobStore,
    private val expectedEvaluationId: String,
    private val expectedDatasetSha256: String,
    private val expectedBaselineArtifactSha256: String,
    private val minimumSamples: Int,
) {
    init {
        require(expectedEvaluationId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        require(expectedDatasetSha256.matches(Regex("[0-9a-f]{64}")))
        require(expectedBaselineArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(minimumSamples in 30..1_000_000) { "minimumSamples outside allowed range" }
    }

    fun verify(receipt: SkillReceipt): VerifiedHoldoutProof {
        fun reject(reason: String) = VerifiedHoldoutProof(null, null, reason)
        try {
            if (receipt.targetState != SkillLifecycleState.VERIFIED ||
                receipt.kind != SkillReceiptKind.EXTERNAL_GROUND_TRUTH ||
                receipt.regressionFailures != 0
            ) return reject("invalid_verification_receipt")
            val first = signedIndex.find(receipt.receiptId) ?: return reject("evidence_index_missing")
            if (first.receipt != receipt) return reject("evidence_binding_mismatch")
            if (!authority.authenticateEnvelope(first)) return reject("evidence_not_authenticated")
            if (signedIndex.find(receipt.receiptId) != first) return reject("evidence_index_changed")

            val bytes = evidenceStore.load(receipt.evidenceRef) ?: return reject("evidence_blob_missing")
            if (bytes.size > 4096) return reject("invalid_holdout_payload")
            val expected = first.evidenceSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), expected)) {
                return reject("evidence_hash_changed")
            }
            val observation = SkillHoldoutEvidenceCodec.decode(bytes) ?: return reject("invalid_holdout_payload")
            if (observation.evaluationId != expectedEvaluationId ||
                observation.datasetSha256 != expectedDatasetSha256 ||
                observation.baselineArtifactSha256 != expectedBaselineArtifactSha256
            ) return reject("benchmark_scope_mismatch")
            if (observation.sampleSize < minimumSamples) return reject("insufficient_sample_size")
            return VerifiedHoldoutProof(observation, first.evidenceSha256, "authenticated_typed_holdout")
        } catch (_: Exception) {
            return reject("authority_or_storage_failure")
        }
    }
}

/** Reused by verified admission and authoritative selection; one bound, one statistical policy. */
/**
 * Host-pinned upper bound on all hypotheses screened against this evaluation dataset,
 * including candidates considered in previous calls. This does NOT enforce preregistration;
 * a durable external evaluator must bind and audit the family across sessions.
 */
data class FamilywiseEvidenceBudget(val registeredHypotheses: Int) {
    init {
        require(registeredHypotheses in 1..1_000_000) { "invalid preregistered hypothesis count" }
    }
}

/** Bonferroni + one-sided Hoeffding: family-wise false-positive risk <= 0.05. */
internal object SkillHoldoutConfidence {
    fun observedGain(observation: SkillHoldoutObservation): Double =
        (observation.wins - observation.losses).toDouble() / observation.sampleSize

    fun lowerBoundGain(observation: SkillHoldoutObservation, budget: FamilywiseEvidenceBudget): Double =
        observedGain(observation) - sqrt(2.0 * ln(20.0 * budget.registeredHypotheses) / observation.sampleSize)
}

/**
 * Production-oriented gate: typed, properly signed, current, preregistered paired holdout is
 * mandatory for VERIFIED. The host must control signer pins, benchmark identity and blob custody.
 */
class PinnedHoldoutVerifiedAdmission(
    authority: PinnedEd25519EvidenceAuthority,
    signedIndex: SignedSkillEvidenceIndex,
    evidenceStore: SkillEvidenceBlobStore,
    expectedEvaluationId: String,
    expectedDatasetSha256: String,
    expectedBaselineArtifactSha256: String,
    private val familywiseBudget: FamilywiseEvidenceBudget,
    minimumSamples: Int = 100,
) : VerifiedAdmissionPolicy {
    private val proof = PinnedHoldoutProofVerifier(
        authority, signedIndex, evidenceStore,
        expectedEvaluationId, expectedDatasetSha256, expectedBaselineArtifactSha256, minimumSamples,
    )

    override fun admit(receipt: SkillReceipt): PromotionDecision {
        val checked = proof.verify(receipt)
        val observation = checked.observation ?: return PromotionDecision(false, checked.reason)
        if (SkillHoldoutConfidence.lowerBoundGain(observation, familywiseBudget) <= 0.0) {
            return PromotionDecision(false, "no_confident_positive_gain")
        }
        return PromotionDecision(true, "verified_holdout_admitted")
    }
}
