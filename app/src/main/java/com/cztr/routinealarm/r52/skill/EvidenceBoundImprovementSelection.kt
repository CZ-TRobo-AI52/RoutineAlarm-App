package com.cztr.routinealarm.r52.skill

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * An externally attested, matched holdout observation. Wins/losses/ties are paired outcomes on
 * the same dataset; costs are attested by the holdout issuer, not accepted from a caller.
 *
 * This evidence format cannot establish that the evaluator's sampling was independent or that
 * its task labels were correct. Host-side custody, preregistration and holdout policy are required.
 */
data class SkillHoldoutObservation(
    val evaluationId: String,
    val datasetSha256: String,
    val baselineArtifactSha256: String,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val tokenCost: Long,
    val latencyMs: Long,
    val computeMicrounits: Long,
    val complexityMicrounits: Long,
    val riskMicrounits: Long,
) {
    val sampleSize: Long get() = wins.toLong() + losses + ties

    init {
        require(evaluationId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "evaluationId required" }
        require(datasetSha256.matches(Regex("[0-9a-f]{64}"))) { "dataset fingerprint required" }
        require(baselineArtifactSha256.matches(Regex("[0-9a-f]{64}"))) { "baseline fingerprint required" }
        require(wins >= 0 && losses >= 0 && ties >= 0) { "outcomes must be non-negative" }
        require(sampleSize in 1..1_000_000L) { "sample size outside policy limit" }
        require(listOf(tokenCost, latencyMs, computeMicrounits, complexityMicrounits, riskMicrounits).all { it >= 0 }) {
            "costs must be non-negative"
        }
    }
}

/** V1 evidence bytes: length-prefixed UTF-8 fields + fixed-width big-endian integer counters. */
object SkillHoldoutEvidenceCodec {
    private const val DOMAIN = "R52-PAIRED-HOLDOUT/1"
    private const val MAX_BYTES = 4096
    private const val MAX_TEXT_BYTES = 256

    fun encode(o: SkillHoldoutObservation): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            fun field(value: String) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_TEXT_BYTES)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            field(DOMAIN)
            field(o.evaluationId)
            field(o.datasetSha256)
            field(o.baselineArtifactSha256)
            data.writeInt(o.wins)
            data.writeInt(o.losses)
            data.writeInt(o.ties)
            data.writeLong(o.tokenCost)
            data.writeLong(o.latencyMs)
            data.writeLong(o.computeMicrounits)
            data.writeLong(o.complexityMicrounits)
            data.writeLong(o.riskMicrounits)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): SkillHoldoutObservation? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return runCatching {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            fun field(): String {
                val length = input.readInt()
                require(length in 1..MAX_TEXT_BYTES && length <= input.available())
                val raw = ByteArray(length)
                input.readFully(raw)
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString()
            }
            require(field() == DOMAIN) { "holdout codec version mismatch" }
            val o = SkillHoldoutObservation(
                evaluationId = field(),
                datasetSha256 = field(),
                baselineArtifactSha256 = field(),
                wins = input.readInt(), losses = input.readInt(), ties = input.readInt(),
                tokenCost = input.readLong(), latencyMs = input.readLong(),
                computeMicrounits = input.readLong(),
                complexityMicrounits = input.readLong(), riskMicrounits = input.readLong(),
            )
            require(input.available() == 0) { "trailing bytes not permitted" }
            o
        }.getOrNull()
    }
}

/** Only derived from a currently re-authenticated signed, registry-accepted ground-truth receipt. */
data class EvidenceBoundImprovement(
    val skillId: String,
    val version: SkillVersion,
    val receiptId: String,
    val evidenceSha256: String,
    val sampleSize: Long,
    val observedGain: Double,
    val lowerBoundGain: Double,
    val authenticatedRoi: Double,
)

data class EvidenceBoundImprovementDecision(
    val winner: EvidenceBoundImprovement?,
    val accepted: List<EvidenceBoundImprovement>,
    val rejected: Map<String, String>,
    val reason: String,
)

/** Host-selected holdout scope; candidate inputs cannot change the benchmark or baseline. */
data class EvidenceBoundSelectionRequest(
    val evaluationId: String,
    val datasetSha256: String,
    val baselineArtifactSha256: String,
    val minimumSamples: Int = 100,
)

/**
 * A non-advisory counterpart to RecursiveImprovementKernel.chooseBest.
 * Never upgrades an ImprovementCandidate(validated=true) or raw SkillEvalResult into evidence.
 */
class EvidenceBoundImprovementSelector(
    private val registry: SkillRegistry,
    private val authority: PinnedEd25519EvidenceAuthority,
    private val signedIndex: SignedSkillEvidenceIndex,
    private val evidenceStore: SkillEvidenceBlobStore,
    private val expectedEvaluationId: String,
    private val expectedDatasetSha256: String,
    private val expectedBaselineArtifactSha256: String,
    private val familywiseBudget: FamilywiseEvidenceBudget,
    private val minimumSamples: Int = 100,
) {
    init {
        // Validation lives in the shared verifier so both admission and selection enforce one policy.
    }

    private val verifier = PinnedHoldoutProofVerifier(
        authority, signedIndex, evidenceStore,
        expectedEvaluationId, expectedDatasetSha256, expectedBaselineArtifactSha256, minimumSamples,
    )

    fun chooseBest(skillIds: Collection<String>): EvidenceBoundImprovementDecision {
        val uniqueIds = skillIds.distinct().sorted()
        if (uniqueIds.size > familywiseBudget.registeredHypotheses) {
            return EvidenceBoundImprovementDecision(
                winner = null, accepted = emptyList(),
                rejected = uniqueIds.associateWith { "comparison_family_budget_exceeded" },
                reason = "comparison_family_budget_exceeded",
            )
        }
        val accepted = mutableListOf<EvidenceBoundImprovement>()
        val rejected = linkedMapOf<String, String>()
        for (skillId in uniqueIds) {
            val (value, reason) = try {
                evaluate(skillId)
            } catch (_: Exception) {
                null to "authority_or_storage_failure"
            }
            if (value == null) rejected[skillId] = reason else accepted += value
        }
        val winner = accepted.maxWithOrNull(
            compareBy<EvidenceBoundImprovement> { it.authenticatedRoi }
                .thenBy { it.lowerBoundGain }
                .thenByDescending { it.skillId },
        )
        return EvidenceBoundImprovementDecision(
            winner = winner,
            accepted = accepted.toList(),
            rejected = rejected.toMap(),
            reason = if (winner == null) "no_evidence_bound_positive_gain" else "highest_evidence_bound_roi",
        )
    }

    private fun evaluate(skillId: String): Pair<EvidenceBoundImprovement?, String> {
        val receipt = registry.verifiedReceiptFor(skillId) ?: return null to "skill_not_verified"
        val checked = verifier.verify(receipt)
        val observation = checked.observation ?: return null to checked.reason
        val observed = SkillHoldoutConfidence.observedGain(observation)
        val lower = SkillHoldoutConfidence.lowerBoundGain(observation, familywiseBudget)
        if (lower <= 0.0) return null to "no_confident_positive_gain"
        val burden = 1.0 + observation.tokenCost / 1000.0 + observation.latencyMs / 1000.0 +
            observation.computeMicrounits / 1_000_000.0 + observation.complexityMicrounits / 1_000_000.0 +
            observation.riskMicrounits / 1_000_000.0
        if (!burden.isFinite() || burden <= 0.0) return null to "invalid_observed_cost"
        val roi = lower / burden
        if (!roi.isFinite() || roi <= 0.0) return null to "invalid_observed_cost"
        return EvidenceBoundImprovement(
            skillId = skillId, version = receipt.version, receiptId = receipt.receiptId,
            evidenceSha256 = checked.evidenceSha256!!, sampleSize = observation.sampleSize,
            observedGain = observed, lowerBoundGain = lower, authenticatedRoi = roi,
        ) to "accepted"
    }
}
