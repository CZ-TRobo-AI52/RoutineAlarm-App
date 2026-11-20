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

/** C005: protect the transition to VERIFIED, rather than only filtering later selection. */
class R52VerifiedAdmissionBoundaryTest {
    private val now = 1_800_000_000L
    private val commit = "fd1f5925a6c54a4dd5ef1420b26e3f0f23929e36"
    private val audience = "github:CZ-TRobo-AI52/RoutineAlarm-App@codex/routinealarm-alpha14-integration"
    private val evaluation = "r52-pre-registered-holdout-C005"
    private val dataset = "d".repeat(64)
    private val baseline = "b".repeat(64)

    private inner class Fixture {
        private val buildKey: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val holdoutKey: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val blobs = linkedMapOf<String, ByteArray>()
        val signed = linkedMapOf<String, SignedSkillEvidence>()
        val index = SignedSkillEvidenceIndex { signed[it] }
        val store = SkillEvidenceBlobStore { blobs[it] }
        private fun key(pair: KeyPair) = Base64.getEncoder().encodeToString(pair.public.encoded)
        fun authority(storeOverride: SkillEvidenceBlobStore = store) = PinnedEd25519EvidenceAuthority(
            expectedAudience = audience,
            expectedSourceCommitSha = commit,
            issuers = listOf(
                TrustedEvidenceIssuer("forge-build", key(buildKey), SkillReceiptKind.values().toSet() - SkillReceiptKind.EXTERNAL_GROUND_TRUTH),
                TrustedEvidenceIssuer("independent-holdout", key(holdoutKey), setOf(SkillReceiptKind.EXTERNAL_GROUND_TRUTH), independentGroundTruth = true),
            ),
            signedEvidenceIndex = index,
            evidenceStore = storeOverride,
            clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC),
        )
        fun verifiedPolicy(authorityOverride: PinnedEd25519EvidenceAuthority = authority(),
            storeOverride: SkillEvidenceBlobStore = store,
            expectedDataset: String = dataset,
            familySize: Int = 1,
        ) = PinnedHoldoutVerifiedAdmission(
            authority = authorityOverride, signedIndex = index, evidenceStore = storeOverride,
            expectedEvaluationId = evaluation, expectedDatasetSha256 = expectedDataset,
            expectedBaselineArtifactSha256 = baseline, familywiseBudget = FamilywiseEvidenceBudget(familySize), minimumSamples = 100,
        )
        fun skill(id: String = "r52-verified") = SkillDescriptor(
            id = id, version = SkillVersion(1, 0, 0), purpose = "evaluate reusable skill",
            positiveTriggers = setOf("evaluate"), negativeTriggers = emptySet(),
            capabilities = setOf("evaluation"), dependencies = emptySet(),
            state = SkillLifecycleState.PROPOSED,
            provenance = SkillProvenance(null, "H-C005", setOf(evaluation), "typed verified boundary"),
        )
        fun payload(
            eval: String = evaluation, data: String = dataset, base: String = baseline,
            wins: Int = 900, losses: Int = 100, ties: Int = 0,
        ): ByteArray = SkillHoldoutEvidenceCodec.encode(SkillHoldoutObservation(
            eval, data, base, wins, losses, ties,
            tokenCost = 100, latencyMs = 200,
            computeMicrounits = 100, complexityMicrounits = 100, riskMicrounits = 100,
        ))
        fun issue(skill: SkillDescriptor, stage: SkillLifecycleState, sequence: Int, payload: ByteArray): SkillReceipt {
            val receipt = SkillReceipt(
                receiptId = "${skill.id}-c005-$sequence", skillId = skill.id, version = skill.version,
                artifactSha256 = SkillFingerprint.of(skill), targetState = stage,
                kind = when (stage) {
                    SkillLifecycleState.IMPLEMENTED -> SkillReceiptKind.IMPLEMENTATION
                    SkillLifecycleState.TESTED -> SkillReceiptKind.TEST_PASS
                    SkillLifecycleState.BENCHMARKED -> SkillReceiptKind.BENCHMARK_PASS
                    SkillLifecycleState.DEPLOYED -> SkillReceiptKind.DEPLOYMENT
                    SkillLifecycleState.VERIFIED -> SkillReceiptKind.EXTERNAL_GROUND_TRUTH
                    else -> error("no receipt for proposed")
                },
                evidenceRef = "forge://c005/${skill.id}/$sequence",
                issuerId = if (stage == SkillLifecycleState.VERIFIED) "independent-holdout" else "forge-build",
            )
            blobs[receipt.evidenceRef] = payload
            val hash = MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val unsigned = SignedSkillEvidence(
                receipt = receipt, audience = audience, sourceCommitSha = commit,
                issuedAtEpochSeconds = now - 60, expiresAtEpochSeconds = now + 600,
                evidenceSha256 = hash, signatureBase64 = "",
            )
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(if (stage == SkillLifecycleState.VERIFIED) holdoutKey.private else buildKey.private)
            signer.update(SkillEvidenceCanonicalCodec.payload(unsigned))
            signed[receipt.receiptId] = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()))
            return receipt
        }
        fun deployed(registry: SkillRegistry, id: String = "r52-verified"): SkillDescriptor {
            val skill = skill(id)
            assertEquals(RegistrationResult.ADDED, registry.register(skill))
            SkillLifecycleState.values().drop(1).dropLast(1).forEachIndexed { i, stage ->
                val receipt = issue(skill, stage, i, "build-pass".toByteArray())
                assertTrue(registry.promote(skill.id, stage, receipt).allowed)
            }
            assertEquals(SkillLifecycleState.DEPLOYED, registry.get(skill.id)!!.state)
            return skill
        }
    }

    @Test fun defaultRegistryMustNotAcceptVerifiedEvenWithValidSignature() {
        val f = Fixture()
        val registry = SkillRegistry(f.authority())
        val skill = f.deployed(registry)
        val r = f.issue(skill, SkillLifecycleState.VERIFIED, 4, f.payload())
        assertEquals("verified_admission_policy_required", registry.promote(skill.id, SkillLifecycleState.VERIFIED, r).reason)
        assertEquals(SkillLifecycleState.DEPLOYED, registry.get(skill.id)!!.state)
        assertNull(registry.verifiedReceiptFor(skill.id))
    }

    @Test fun authenticTypedHoldoutCanCrossVerifiedBoundary() {
        val f = Fixture()
        val registry = SkillRegistry(f.authority(), f.verifiedPolicy())
        val skill = f.deployed(registry)
        val receipt = f.issue(skill, SkillLifecycleState.VERIFIED, 4, f.payload())
        assertEquals("authenticated_transition", registry.promote(skill.id, SkillLifecycleState.VERIFIED, receipt).reason)
        assertEquals(SkillLifecycleState.VERIFIED, registry.get(skill.id)!!.state)
        assertEquals(receipt, registry.verifiedReceiptFor(skill.id))
        assertEquals("idempotent_replay", registry.promote(skill.id, SkillLifecycleState.VERIFIED, receipt).reason)
    }

    @Test fun arbitrarySignedJsonIsRejectedAtAdmissionNotOnlyLaterSelection() {
        val f = Fixture()
        val registry = SkillRegistry(f.authority(), f.verifiedPolicy())
        val skill = f.deployed(registry)
        val receipt = f.issue(skill, SkillLifecycleState.VERIFIED, 4, "{\"pass\":true}".toByteArray())
        assertEquals("invalid_holdout_payload", registry.promote(skill.id, SkillLifecycleState.VERIFIED, receipt).reason)
        assertNull(registry.verifiedReceiptFor(skill.id))
    }

    @Test fun evenAuthenticDifferentEvaluationDatasetOrBaselineCannotPromote() {
        for (bad in listOf(
            Fixture().payload(eval = "easy-benchmark"),
            Fixture().payload(data = "e".repeat(64)),
            Fixture().payload(base = "c".repeat(64)),
        )) {
            val f = Fixture()
            val registry = SkillRegistry(f.authority(), f.verifiedPolicy())
            val skill = f.deployed(registry)
            val receipt = f.issue(skill, SkillLifecycleState.VERIFIED, 4, bad)
            assertEquals("benchmark_scope_mismatch", registry.promote(skill.id, SkillLifecycleState.VERIFIED, receipt).reason)
        }
    }

    @Test fun tooFewSamplesOrNoPositiveConfidenceLowerBoundCannotPromote() {
        for ((payload, expected) in listOf(
            Fixture().payload(wins = 49, losses = 0) to "insufficient_sample_size",
            Fixture().payload(wins = 51, losses = 49) to "no_confident_positive_gain",
        )) {
            val f = Fixture()
            val registry = SkillRegistry(f.authority(), f.verifiedPolicy())
            val skill = f.deployed(registry)
            val receipt = f.issue(skill, SkillLifecycleState.VERIFIED, 4, payload)
            assertEquals(expected, registry.promote(skill.id, SkillLifecycleState.VERIFIED, receipt).reason)
        }
    }

    @Test fun signedEvidenceReplacedAfterFirstReadIsRejected() {
        val f = Fixture()
        val original = f.payload()
        val modified = f.payload(wins = 700, losses = 300)
        var reads = 0
        val unstable = SkillEvidenceBlobStore { key ->
            if (!key.endsWith("/4")) f.blobs[key]
            else if (++reads == 1) original else modified
        }
        val authority = f.authority(storeOverride = unstable)
        val registry = SkillRegistry(authority, f.verifiedPolicy(authority, unstable))
        val skill = f.deployed(registry)
        val r = f.issue(skill, SkillLifecycleState.VERIFIED, 4, original)
        assertFalse(registry.promote(skill.id, SkillLifecycleState.VERIFIED, r).allowed)
        assertNull(registry.verifiedReceiptFor(skill.id))
        assertTrue(reads >= 2)
    }

    @Test fun missingSignedIndexOrCorruptBlobBlocksVerification() {
        val f = Fixture()
        val registry = SkillRegistry(f.authority(), f.verifiedPolicy())
        val skill = f.deployed(registry)
        val r = f.issue(skill, SkillLifecycleState.VERIFIED, 4, f.payload())
        f.signed.remove(r.receiptId)
        assertEquals("receipt_not_authenticated", registry.promote(skill.id, SkillLifecycleState.VERIFIED, r).reason)
        f.issue(skill, SkillLifecycleState.VERIFIED, 4, f.payload())
        f.blobs[r.evidenceRef] = "corruption".toByteArray()
        assertEquals("receipt_not_authenticated", registry.promote(skill.id, SkillLifecycleState.VERIFIED, r).reason)
    }

    @Test fun policyFailureIsDeniedWithoutStateChange() {
        val f = Fixture()
        val registry = SkillRegistry(f.authority(), VerifiedAdmissionPolicy { throw IllegalStateException("broken") })
        val skill = f.deployed(registry)
        val r = f.issue(skill, SkillLifecycleState.VERIFIED, 4, f.payload())
        assertEquals("verified_admission_failure", registry.promote(skill.id, SkillLifecycleState.VERIFIED, r).reason)
        assertEquals(SkillLifecycleState.DEPLOYED, registry.get(skill.id)!!.state)
    }

    @Test fun acceptedReceiptCannotBeReplayedAcrossVersionUpgrade() {
        val f = Fixture()
        val registry = SkillRegistry(f.authority(), f.verifiedPolicy())
        val skill = f.deployed(registry)
        val r = f.issue(skill, SkillLifecycleState.VERIFIED, 4, f.payload())
        assertTrue(registry.promote(skill.id, SkillLifecycleState.VERIFIED, r).allowed)
        val updated = skill.copy(
            version = SkillVersion(1, 0, 1),
            provenance = skill.provenance.copy(parentVersion = skill.version),
        )
        assertEquals(RegistrationResult.UPDATED, registry.register(updated))
        assertNull(registry.verifiedReceiptFor(skill.id))
        assertEquals("stale_receipt_replay", registry.promote(skill.id, SkillLifecycleState.VERIFIED, r).reason)
    }
    @Test fun signedNullWinnerCannotCrossVerifiedWithPreregisteredFamilyBudget() {
        val f = Fixture()
        val registry = SkillRegistry(f.authority(), f.verifiedPolicy(familySize = 500))
        val skill = f.deployed(registry)
        val receipt = f.issue(skill, SkillLifecycleState.VERIFIED, 4, f.payload(wins = 63, losses = 37))
        assertEquals("no_confident_positive_gain", registry.promote(skill.id, SkillLifecycleState.VERIFIED, receipt).reason)
        assertEquals(SkillLifecycleState.DEPLOYED, registry.get(skill.id)!!.state)
        assertNull(registry.verifiedReceiptFor(skill.id))
    }

}
