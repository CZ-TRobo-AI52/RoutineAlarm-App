package com.cztr.routinealarm.r52.skill

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Host-owned identity for one evidence-bound comparison family.
 *
 * The digest is tamper-evident, not an authentication mechanism by itself.
 * The trusted host must authenticate/persist this scope before selection.
 */
data class PreregisteredSelectionScope(
    val scopeId: String,
    val evaluationId: String,
    val datasetSha256: String,
    val baselineArtifactSha256: String,
    val candidateSetSha256: String,
    val registeredHypotheses: Int,
    val sourceCommitSha256: String,
    val scopeDigestSha256: String,
) {
    init {
        require(scopeId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "scopeId required" }
        require(evaluationId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "evaluationId required" }
        require(datasetSha256.matches(Regex("[0-9a-f]{64}"))) { "dataset fingerprint required" }
        require(baselineArtifactSha256.matches(Regex("[0-9a-f]{64}"))) { "baseline fingerprint required" }
        require(candidateSetSha256.matches(Regex("[0-9a-f]{64}"))) { "candidate-set fingerprint required" }
        require(registeredHypotheses >= 1) { "registered hypothesis count required" }
        require(sourceCommitSha256.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) { "source commit fingerprint required" }
        require(scopeDigestSha256.matches(Regex("[0-9a-f]{64}"))) { "scope digest required" }
    }

    fun validate(): Boolean = scopeDigestSha256 == computeDigest(
        scopeId, evaluationId, datasetSha256, baselineArtifactSha256,
        candidateSetSha256, registeredHypotheses, sourceCommitSha256,
    )

    companion object {
        private const val DOMAIN = "R52-SELECTION-SCOPE/1"

        fun canonicalCandidateSetSha256(candidateIds: Collection<String>): String {
            val canonical = candidateIds
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .sorted()
                .joinToString("\n")
            return sha256("$DOMAIN/CANDIDATES\n$canonical")
        }

        fun create(
            scopeId: String,
            evaluationId: String,
            datasetSha256: String,
            baselineArtifactSha256: String,
            candidateIds: Collection<String>,
            registeredHypotheses: Int,
            sourceCommitSha256: String,
        ): PreregisteredSelectionScope {
            val candidateSetSha256 = canonicalCandidateSetSha256(candidateIds)
            val digest = computeDigest(
                scopeId, evaluationId, datasetSha256, baselineArtifactSha256,
                candidateSetSha256, registeredHypotheses, sourceCommitSha256,
            )
            return PreregisteredSelectionScope(
                scopeId, evaluationId, datasetSha256, baselineArtifactSha256,
                candidateSetSha256, registeredHypotheses, sourceCommitSha256, digest,
            )
        }

        fun computeDigest(
            scopeId: String,
            evaluationId: String,
            datasetSha256: String,
            baselineArtifactSha256: String,
            candidateSetSha256: String,
            registeredHypotheses: Int,
            sourceCommitSha256: String,
        ): String {
            val canonical = listOf(
                DOMAIN, scopeId, evaluationId, datasetSha256, baselineArtifactSha256,
                candidateSetSha256, registeredHypotheses.toString(), sourceCommitSha256,
            ).joinToString("\n")
            return sha256(canonical)
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
