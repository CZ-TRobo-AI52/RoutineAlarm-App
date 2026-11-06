package com.cztr.routinealarm.r52.skill

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C004: a caller's "validated=true" is NOT sufficient evidence for an authoritative decision. */
class R52EvidenceBoundSelectionTest {
    private val now = 1_800_000_000L
    private val scope = "github:CZ-TRobo-AI52/RoutineAlarm-App@codex/routinealarm-alpha14-integration"
    private val commit = "fd1f5925a6c54a4dd5ef1420b26e3f0f23929e36"
    private val dataset = "d".repeat(64)
    private val baseline = "b".repeat(64)
    private val eval = "holdout-2026-11-02-C004"

    private class Harness(val now: Long, val scope: String, val commit: String) {
        val builder: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val holdout: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val receipts = linkedMapOf<String, SignedSkillEvidence>()
        val blobs = linkedMapOf<String, ByteArray>()
        val index = SignedSkillEvidenceIndex { receipts[it] }
        val store = SkillEvidenceBlobStore { blobs[it] }
        private fun key(pair: KeyPair): String = Base64.getEncoder().encodeToString(pair.public.encoded)
        fun authority(sourceCommit: String = commit, allowedHoldout: Boolean = true,
            blobStore: SkillEvidenceBlobStore = store) =
            PinnedEd25519EvidenceAuthority(
                expectedAudience = scope, expectedSourceCommitSha = sourceCommit,
                issuers = listOf(
                    TrustedEvidenceIssuer("forge-build", key(builder), SkillReceiptKind.values().toSet() - SkillReceiptKind.EXTERNAL_GROUND_TRUTH),
                    TrustedEvidenceIssuer("independent-holdout", key(holdout), setOf(SkillReceiptKind.EXTERNAL_GROUND_TRUTH), independentGroundTruth = allowedHoldout),
                ),
                signedEvidenceIndex = index, evidenceStore = blobStore,
                clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC),
            )

        fun sign(receipt: SkillReceipt, payload: ByteArray, issuedAt: Long = now - 60, expiresAt: Long = now + 600) {
            blobs[receipt.evidenceRef] = payload
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val unsigned = SignedSkillEvidence(receipt, scope, commit, issuedAt, expiresAt, digest, "")
            val s = Signature.getInstance("Ed25519")
            s.initSign(if (receipt.kind == SkillReceiptKind.EXTERNAL_GROUND_TRUTH) holdout.private else builder.private)
            s.update(SkillEvidenceCanonicalCodec.payload(unsigned))
            receipts[receipt.receiptId] = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(s.sign()))
        }

        fun skill(id: String) = SkillDescriptor(
            id = id, version = SkillVersion(1, 0, 0), purpose = "R52 improvement candidate",
            positiveTriggers = setOf("improve"), negativeTriggers = emptySet(), capabilities = setOf("r52"),
            dependencies = emptySet(), state = SkillLifecycleState.PROPOSED,
            provenance = SkillProvenance(null, "H-C004", setOf("E-C004"), "evidence-bound selection"),
        )

        fun promoteThroughAllStages(registry: SkillRegistry, skill: SkillDescriptor, finalPayload: ByteArray) {
            assertEquals(RegistrationResult.ADDED, registry.register(skill))
            for ((i, stage) in SkillLifecycleState.values().drop(1).withIndex()) {
                val kind = when (stage) {
                    SkillLifecycleState.IMPLEMENTED -> SkillReceiptKind.IMPLEMENTATION
                    SkillLifecycleState.TESTED -> SkillReceiptKind.TEST_PASS
                    SkillLifecycleState.BENCHMARKED -> SkillReceiptKind.BENCHMARK_PASS
                    SkillLifecycleState.DEPLOYED -> SkillReceiptKind.DEPLOYMENT
                    SkillLifecycleState.VERIFIED -> SkillReceiptKind.EXTERNAL_GROUND_TRUTH
                    else -> error("Unexpected stage")
                }
                val receipt = SkillReceipt(
                    receiptId = "${skill.id}-stage-$i", skillId = skill.id, version = skill.version,
                    artifactSha256 = SkillFingerprint.of(skill), targetState = stage,
                    kind = kind, evidenceRef = "forge://evidence/${skill.id}/$i",
                    issuerId = if (stage == SkillLifecycleState.VERIFIED) "independent-holdout" else "forge-build",
                )
                sign(receipt, if (stage == SkillLifecycleState.VERIFIED) finalPayload else "{\"status\":\"pass\"}".toByteArray())
                assertTrue(registry.promote(skill.id, stage, receipt).allowed)
            }
        }
    }

    private fun outcome(
        wins: Int = 950, losses: Int = 50, ties: Int = 0,
        tokens: Long = 1_000, latency: Long = 1_000,
        datasetSha: String = dataset, baselineSha: String = baseline, evaluationId: String = eval,
    ) = SkillHoldoutObservation(
        evaluationId = evaluationId, datasetSha256 = datasetSha, baselineArtifactSha256 = baselineSha,
        wins = wins, losses = losses, ties = ties, tokenCost = tokens, latencyMs = latency,
        computeMicrounits = 0, complexityMicrounits = 0, riskMicrounits = 0,
    )

    private fun selector(h: Harness, registry: SkillRegistry, sourceCommit: String = commit, datasetSha: String = dataset) =
        EvidenceBoundImprovementSelector(
            registry = registry, authority = h.authority(sourceCommit = sourceCommit),
            signedIndex = h.index, evidenceStore = h.store,
            expectedEvaluationId = eval, expectedDatasetSha256 = datasetSha,
            expectedBaselineArtifactSha256 = baseline,
            minimumSamples = 100,
        )

    @Test fun unauthenticatedAndUnregisteredClaimsCannotInfluenceSelection() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        val decision = selector(h, r).chooseBest(listOf("fake-skill"))
        assertNull(decision.winner)
        assertEquals("skill_not_verified", decision.rejected["fake-skill"])
        assertEquals("no_evidence_bound_positive_gain", decision.reason)
        val selfClaim = ImprovementCandidate("fake-skill", 500.0, 0.0, 0.0, 0.0, 0.0, validated = true)
        assertTrue(RecursiveImprovementKernel.chooseBest(listOf(selfClaim)) != null) // legacy is ADVISORY only
    }

    @Test fun onlyAcceptedVerifiedReceiptFromRegistryCanBeUsed() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        val s = h.skill("a")
        assertEquals(RegistrationResult.ADDED, r.register(s))
        assertNull(r.verifiedReceiptFor("a"))
        val decision = selector(h, r).chooseBest(listOf("a"))
        assertNull(decision.winner)
        assertEquals("skill_not_verified", decision.rejected["a"])
    }

    @Test fun signedIndependentHoldoutCanSelectPositiveConfidenceBound() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        val s = h.skill("a")
        h.promoteThroughAllStages(r, s, SkillHoldoutEvidenceCodec.encode(outcome()))
        val receipt = r.verifiedReceiptFor("a")
        assertTrue(receipt != null && receipt.kind == SkillReceiptKind.EXTERNAL_GROUND_TRUTH)
        val result = selector(h, r).chooseBest(listOf("a"))
        val winner = result.winner ?: error("expected evidence-bound winner")
        assertEquals("a", winner.skillId)
        assertEquals(receipt!!.receiptId, winner.receiptId)
        assertEquals(1, result.accepted.size)
        assertTrue(winner.lowerBoundGain > 0.0)
        assertTrue(winner.authenticatedRoi > 0.0)
        assertEquals("highest_evidence_bound_roi", result.reason)
    }

    @Test fun signedBlobMutationOrForgedCostNeverInfluencesChoice() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome()))
        val proof = r.verifiedReceiptFor("a")!!
        h.blobs[proof.evidenceRef] = SkillHoldoutEvidenceCodec.encode(outcome(tokens = 0))
        val d = selector(h, r).chooseBest(listOf("a"))
        assertNull(d.winner)
        assertEquals("evidence_not_authenticated", d.rejected["a"])
    }

    @Test fun oldVerifiedStateCannotReuseMissingOrExpiredEvidence() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome()))
        val receipt = r.verifiedReceiptFor("a")!!
        h.receipts.remove(receipt.receiptId)
        assertEquals("evidence_index_missing", selector(h, r).chooseBest(listOf("a")).rejected["a"])
    }

    @Test fun forgedDatasetAndBaselineDoNotCrossBenchmarkBoundary() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome(datasetSha = "e".repeat(64))))
        assertEquals("benchmark_scope_mismatch", selector(h, r).chooseBest(listOf("a")).rejected["a"])
    }

    @Test fun evaluationIdCannotBeSubstitutedByCandidate() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome(evaluationId = "convenient-easy-run")))
        assertEquals("benchmark_scope_mismatch", selector(h, r).chooseBest(listOf("a")).rejected["a"])
    }

    @Test fun tooFewSamplesAndNoConfidentGainFailClosed() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome(wins = 40, losses = 0)))
        val d = selector(h, r).chooseBest(listOf("a"))
        assertNull(d.winner)
        assertEquals("insufficient_sample_size", d.rejected["a"])
        val h2 = Harness(now, scope, commit)
        val r2 = SkillRegistry(h2.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h2.promoteThroughAllStages(r2, h2.skill("b"), SkillHoldoutEvidenceCodec.encode(outcome(wins = 51, losses = 49)))
        assertEquals("no_confident_positive_gain", selector(h2, r2).chooseBest(listOf("b")).rejected["b"])
    }

    @Test fun selectionUsesSignedCostsAndPenalizesExpensiveCandidates() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("fast"), SkillHoldoutEvidenceCodec.encode(outcome(wins = 900, losses = 100, tokens = 1_000, latency = 1_000)))
        h.promoteThroughAllStages(r, h.skill("slow"), SkillHoldoutEvidenceCodec.encode(outcome(wins = 990, losses = 10, tokens = 1_000_000, latency = 1_000_000)))
        val d = selector(h, r).chooseBest(listOf("slow", "fast"))
        assertEquals("fast", d.winner!!.skillId)
        assertEquals(2, d.accepted.size)
    }

    @Test fun malformedEvidenceAndUntrustedAuthorityAreDenied() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), "not canonical holdout evidence".toByteArray())
        assertEquals("invalid_holdout_payload", selector(h, r).chooseBest(listOf("a")).rejected["a"])
        assertEquals("evidence_not_authenticated", selector(h, r, sourceCommit = "f".repeat(40)).chooseBest(listOf("a")).rejected["a"])
    }

    @Test fun malformedAndOversizedObservationsFailDecode() {
        assertNull(SkillHoldoutEvidenceCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(SkillHoldoutEvidenceCodec.decode(ByteArray(10_000)))
        val good = SkillHoldoutEvidenceCodec.encode(outcome())
        assertTrue(SkillHoldoutEvidenceCodec.decode(good) != null)
        assertNull(SkillHoldoutEvidenceCodec.decode(good + byteArrayOf(0)))
        assertTrue(runCatching { outcome(wins = -1) }.isFailure)
    }

    @Test fun repeatedSkillIdsAreIdempotentNotWeightedMoreHeavily() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome()))
        val d = selector(h, r).chooseBest(listOf("a", "a", "a"))
        assertEquals(1, d.accepted.size)
    }

    @Test fun entireSelectionFailsClosedIfProviderChangesIndexDuringVerification() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome()))
        val pinned = r.verifiedReceiptFor("a")!!
        val actual = h.receipts[pinned.receiptId]!!
        var lookups = 0
        val unstableIndex = SignedSkillEvidenceIndex { id ->
            lookups += 1
            if (lookups >= 2) null else h.receipts[id]
        }
        val decision = EvidenceBoundImprovementSelector(
            registry = r, authority = h.authority(), signedIndex = unstableIndex,
            evidenceStore = h.store, expectedEvaluationId = eval,
            expectedDatasetSha256 = dataset, expectedBaselineArtifactSha256 = baseline,
        ).chooseBest(listOf("a"))
        assertNull(decision.winner)
        assertEquals("evidence_index_changed", decision.rejected["a"])
        assertTrue(actual.receipt == pinned)
    }
    @Test fun separateUntrustedIndexCannotSupplyDifferentEnvelopeThanPinnedAuthority() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome()))
        val receipt = r.verifiedReceiptFor("a")!!
        val valid = h.receipts[receipt.receiptId]!!
        val attackerIndex = SignedSkillEvidenceIndex { valid.copy(evidenceSha256 = "0".repeat(64)) }
        val d = EvidenceBoundImprovementSelector(
            registry = r, authority = h.authority(), signedIndex = attackerIndex,
            evidenceStore = h.store, expectedEvaluationId = eval,
            expectedDatasetSha256 = dataset, expectedBaselineArtifactSha256 = baseline,
        ).chooseBest(listOf("a"))
        assertNull(d.winner)
        assertEquals("evidence_not_authenticated", d.rejected["a"])
    }

    @Test fun newSkillVersionCannotReuseOldAcceptedVerifiedReceipt() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        val first = h.skill("a")
        h.promoteThroughAllStages(r, first, SkillHoldoutEvidenceCodec.encode(outcome()))
        val successor = first.copy(
            version = SkillVersion(1, 0, 1), state = SkillLifecycleState.PROPOSED,
            provenance = first.provenance.copy(parentVersion = first.version),
        )
        assertEquals(RegistrationResult.UPDATED, r.register(successor))
        assertNull(r.verifiedReceiptFor("a"))
        assertEquals("skill_not_verified", selector(h, r).chooseBest(listOf("a")).rejected["a"])
    }

    @Test fun mainEngineDelegatesToEvidenceBoundSelectorOnSameRegistry() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome()))
        val engine = R52SkillEngine(r)
        val decision = engine.selectEvidenceBoundImprovements(
            skillIds = listOf("a"),
            request = EvidenceBoundSelectionRequest(eval, dataset, baseline, minimumSamples = 100),
            authority = h.authority(), signedIndex = h.index, evidenceStore = h.store,
        )
        assertEquals("a", decision.winner!!.skillId)
    }

    @Test fun evidenceStoreChangingAfterAuthorityCheckIsStillHashChecked() {
        val h = Harness(now, scope, commit)
        val r = SkillRegistry(h.authority(), VerifiedAdmissionPolicy { PromotionDecision(true, "fixture_only") })
        h.promoteThroughAllStages(r, h.skill("a"), SkillHoldoutEvidenceCodec.encode(outcome()))
        val receipt = r.verifiedReceiptFor("a")!!
        val originallySigned = h.blobs[receipt.evidenceRef]!!
        var reads = 0
        val unstableStore = SkillEvidenceBlobStore { ref ->
            if (ref != receipt.evidenceRef) h.blobs[ref]
            else if (++reads == 1) originallySigned
            else SkillHoldoutEvidenceCodec.encode(outcome(tokens = 0))
        }
        val d = EvidenceBoundImprovementSelector(
            registry = r, authority = h.authority(blobStore = unstableStore),
            signedIndex = h.index, evidenceStore = unstableStore,
            expectedEvaluationId = eval, expectedDatasetSha256 = dataset,
            expectedBaselineArtifactSha256 = baseline,
        ).chooseBest(listOf("a"))
        assertNull(d.winner)
        assertEquals("evidence_hash_changed", d.rejected["a"])
        assertEquals(2, reads)
    }

}
