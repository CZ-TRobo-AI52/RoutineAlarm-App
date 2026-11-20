package com.cztr.routinealarm.r52.skill

import java.security.KeyPairGenerator
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract: signature authentication is not an evaluation; trust policy supplies issuer roles. */
class R52SkillSignedEvidenceTest {
    private val now = 1_800_000_000L
    private val scope = "github:CZ-TRobo-AI52/RoutineAlarm-App@codex/routinealarm-alpha14-integration"
    private val expectedSourceCommit = "fd1f5925a6c54a4dd5ef1420b26e3f0f23929e36"

    private fun skill() = SkillDescriptor(
        id = "r52-debug", version = SkillVersion(1, 0, 0), purpose = "diagnosis",
        positiveTriggers = setOf("debug"), negativeTriggers = emptySet(), capabilities = setOf("diagnosis"),
        dependencies = emptySet(), state = SkillLifecycleState.PROPOSED,
        provenance = SkillProvenance(null, "H-C003", setOf("E-C003"), "signed proof binding"),
    )

    private fun receipt(
        skill: SkillDescriptor,
        stage: SkillLifecycleState = SkillLifecycleState.IMPLEMENTED,
        id: String = "r52-receipt-1",
    ) = SkillReceipt(
        receiptId = id, skillId = skill.id, version = skill.version,
        artifactSha256 = SkillFingerprint.of(skill), targetState = stage,
        kind = when (stage) {
            SkillLifecycleState.IMPLEMENTED -> SkillReceiptKind.IMPLEMENTATION
            SkillLifecycleState.TESTED -> SkillReceiptKind.TEST_PASS
            SkillLifecycleState.BENCHMARKED -> SkillReceiptKind.BENCHMARK_PASS
            SkillLifecycleState.DEPLOYED -> SkillReceiptKind.DEPLOYMENT
            SkillLifecycleState.VERIFIED -> SkillReceiptKind.EXTERNAL_GROUND_TRUTH
            SkillLifecycleState.PROPOSED -> error("not promoted")
        },
        evidenceRef = "forge://runs/fixture/evidence.json", issuerId = "fixture-issuer",
    )

    private class Harness(
        val scope: String,
        val now: Long,
        val sourceCommit: String = "fd1f5925a6c54a4dd5ef1420b26e3f0f23929e36",
        val independentHoldout: Boolean = false,
        val allowedKinds: Set<SkillReceiptKind> = SkillReceiptKind.values().toSet(),
    ) {
        val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val stored = linkedMapOf<String, SignedSkillEvidence>()
        val evidence = linkedMapOf<String, ByteArray>()

        fun policy() = TrustedEvidenceIssuer(
            issuerId = "fixture-issuer",
            publicKeyX509Base64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            allowedKinds = allowedKinds,
            independentGroundTruth = independentHoldout,
        )

        fun authority(audience: String = scope, policies: List<TrustedEvidenceIssuer> = listOf(policy()),
            sourceCommit: String = this.sourceCommit) =
            PinnedEd25519EvidenceAuthority(
                expectedAudience = audience, expectedSourceCommitSha = sourceCommit,
                issuers = policies,
                signedEvidenceIndex = SignedSkillEvidenceIndex { stored[it] },
                evidenceStore = SkillEvidenceBlobStore { evidence[it] },
                clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC),
            )

        fun sign(envelope: SignedSkillEvidence): SignedSkillEvidence {
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(keyPair.private)
            signer.update(SkillEvidenceCanonicalCodec.payload(envelope))
            return envelope.copy(signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()))
        }

        fun issue(
            receipt: SkillReceipt,
            audience: String = scope,
            proof: ByteArray = "{\"result\":\"pass\",\"holdout\":\"fixture\"}".toByteArray(),
            issuedAt: Long = now - 30,
            expiresAt: Long = now + 300,
        ): SignedSkillEvidence {
            evidence[receipt.evidenceRef] = proof
            val doc = SignedSkillEvidence(
                receipt = receipt, audience = audience, sourceCommitSha = sourceCommit,
                issuedAtEpochSeconds = issuedAt, expiresAtEpochSeconds = expiresAt,
                evidenceSha256 = MessageDigest.getInstance("SHA-256").digest(proof)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) },
                signatureBase64 = "",
            )
            return sign(doc).also { stored[receipt.receiptId] = it }
        }
    }

    @Test fun validSignatureAndStoredEvidencePermitExactlyOneStage() {
        val candidate = skill()
        val h = Harness(scope, now)
        val registry = SkillRegistry(h.authority())
        assertEquals(RegistrationResult.ADDED, registry.register(candidate))
        val r = receipt(candidate)
        h.issue(r)
        assertTrue(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, r).allowed)
        assertEquals(SkillLifecycleState.IMPLEMENTED, registry.get(candidate.id)!!.state)
        assertEquals("idempotent_replay", registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, r).reason)
    }

    @Test fun missingIndexOrStoreFailsClosedWithoutMutation() {
        val candidate = skill()
        val h = Harness(scope, now)
        val registry = SkillRegistry(h.authority())
        registry.register(candidate)
        val r = receipt(candidate)
        assertFalse(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, r).allowed)
        h.issue(r)
        h.evidence.clear()
        assertFalse(registry.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, r).allowed)
        assertEquals(SkillLifecycleState.PROPOSED, registry.get(candidate.id)!!.state)
    }

    @Test fun tamperedReceiptFieldsInvalidateSignatureAndBinding() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        h.issue(r)
        assertFalse(h.authority().authenticate(r.copy(evidenceRef = "forge://runs/other")))
        assertFalse(h.authority().authenticate(r.copy(issuerId = "other")))
        assertFalse(h.authority().authenticate(r.copy(regressionFailures = 1)))
    }

    @Test fun tamperedSignatureCannotBeAccepted() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        val signed = h.issue(r)
        h.stored[r.receiptId] = signed.copy(signatureBase64 = Base64.getEncoder().encodeToString(ByteArray(64)))
        assertFalse(h.authority().authenticate(r))
    }

    @Test fun evidenceBlobMutationIsDetectedEvenWhenSignatureValid() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        h.issue(r)
        h.evidence[r.evidenceRef] = "failed-run".toByteArray()
        assertFalse(h.authority().authenticate(r))
    }

    @Test fun signedEvidenceCannotBeReplayedIntoAnotherAudience() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        h.issue(r)
        assertFalse(h.authority("github:another/repository@main").authenticate(r))
    }

    @Test fun validSignatureCannotCrossSourceCommitBoundary() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        h.issue(r)
        assertFalse(h.authority(sourceCommit = "f".repeat(40)).authenticate(r))
    }

    @Test fun unknownIssuerOrWrongPublicKeyCannotPass() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        h.issue(r)
        assertFalse(h.authority(policies = emptyList()).authenticate(r))
        val anotherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public
        assertFalse(h.authority(policies = listOf(h.policy().copy(
            publicKeyX509Base64 = Base64.getEncoder().encodeToString(anotherKey.encoded),
        ))).authenticate(r))
    }

    @Test fun expiredFutureDatedAndUnreasonablyLongReceiptsFail() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        h.issue(r, issuedAt = now - 500, expiresAt = now - 1)
        assertFalse(h.authority().authenticate(r))
        h.issue(r, issuedAt = now + 61, expiresAt = now + 120)
        assertFalse(h.authority().authenticate(r))
        h.issue(r, issuedAt = now - 1, expiresAt = now + 86_400)
        assertFalse(h.authority().authenticate(r))
    }

    @Test fun malformedSignatureAndInvalidIssuerConfigurationFailClosed() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        val signed = h.issue(r)
        h.stored[r.receiptId] = signed.copy(signatureBase64 = "not-base64??")
        assertFalse(h.authority().authenticate(r))
        assertTrue(runCatching { h.authority(policies = listOf(h.policy().copy(publicKeyX509Base64 = "bad-key"))) }.isFailure)
        assertTrue(runCatching { h.authority(policies = listOf(h.policy(), h.policy())) }.isFailure)
    }

    @Test fun deploymentIssuerCannotClaimIndependentGroundTruth() {
        val candidate = skill()
        val h = Harness(scope, now, independentHoldout = false)
        val r = receipt(candidate, stage = SkillLifecycleState.VERIFIED)
        h.issue(r)
        assertFalse(h.authority().authenticate(r))
    }

    @Test fun trustedHoldoutRoleOnlyAuthenticatesApprovedKinds() {
        val candidate = skill()
        val h = Harness(scope, now, independentHoldout = true,
            allowedKinds = setOf(SkillReceiptKind.EXTERNAL_GROUND_TRUTH))
        val verified = receipt(candidate, stage = SkillLifecycleState.VERIFIED)
        h.issue(verified)
        assertTrue(h.authority().authenticate(verified)) // authentication only, not registry stage progression
        val impl = receipt(candidate)
        h.issue(impl)
        assertFalse(h.authority().authenticate(impl))
    }

    @Test fun signedEvidenceCannotPromoteWithoutCompletePriorHistory() {
        val candidate = skill()
        val h = Harness(scope, now, independentHoldout = true)
        val registry = SkillRegistry(h.authority())
        registry.register(candidate)
        val verified = receipt(candidate, stage = SkillLifecycleState.VERIFIED)
        h.issue(verified)
        assertEquals("non_adjacent_transition", registry.promote(candidate.id, SkillLifecycleState.VERIFIED, verified).reason)
        assertEquals(SkillLifecycleState.PROPOSED, registry.get(candidate.id)!!.state)
    }

    @Test fun independentSignedGroundTruthPassesOnlyAfterFullChain() {
        val candidate = skill()
        val h = Harness(scope, now, independentHoldout = true)
        // C003 historically verified signatures only. C005 uses a fixture-only admission to
        // retain this independent signature test; production registries reject untyped evidence.
        val registry = SkillRegistry(h.authority(), VerifiedAdmissionPolicy {
            PromotionDecision(true, "fixture_only")
        })
        registry.register(candidate)
        for ((index, stage) in SkillLifecycleState.values().drop(1).withIndex()) {
            val r = receipt(candidate, stage, id = "stage-$index")
            h.issue(r, proof = "{\"stage\":\"$stage\",\"fixture\":true}".toByteArray())
            assertTrue(registry.promote(candidate.id, stage, r).allowed)
        }
        assertEquals(SkillLifecycleState.VERIFIED, registry.get(candidate.id)!!.state)
    }

    @Test fun authorityExceptionsAreConvertedToDenials() {
        val candidate = skill()
        val h = Harness(scope, now)
        val r = receipt(candidate)
        h.issue(r)
        val down = PinnedEd25519EvidenceAuthority(
            expectedAudience = scope, expectedSourceCommitSha = expectedSourceCommit,
            issuers = listOf(h.policy()),
            signedEvidenceIndex = SignedSkillEvidenceIndex { throw IllegalStateException("network") },
            evidenceStore = SkillEvidenceBlobStore { error("should not be called") },
            clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC),
        )
        val reg = SkillRegistry(down)
        reg.register(candidate)
        assertEquals("receipt_not_authenticated", reg.promote(candidate.id, SkillLifecycleState.IMPLEMENTED, r).reason)
    }
}
