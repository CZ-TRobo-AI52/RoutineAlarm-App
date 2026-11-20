package com.cztr.routinealarm.r52.skill

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64

/** The signed receipt authenticates an issuer's attestation, not the factual correctness of a test. */
data class SignedSkillEvidence(
    val receipt: SkillReceipt,
    val audience: String,
    val sourceCommitSha: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val evidenceSha256: String,
    val signatureBase64: String,
) {
    init {
        require(audience.isNotBlank()) { "audience required" }
        require(sourceCommitSha.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) { "source commit SHA required" }
        require(issuedAtEpochSeconds >= 0 && expiresAtEpochSeconds > issuedAtEpochSeconds) { "invalid evidence window" }
        require(evidenceSha256.matches(Regex("[0-9a-f]{64}"))) { "canonical evidence SHA-256 required" }
    }
}

/** Public trust roots must be provisioned by the host, never accepted from the evidence envelope. */
data class TrustedEvidenceIssuer(
    val issuerId: String,
    val publicKeyX509Base64: String,
    val allowedKinds: Set<SkillReceiptKind>,
    val independentGroundTruth: Boolean = false,
) {
    init {
        require(issuerId.isNotBlank() && publicKeyX509Base64.isNotBlank()) { "issuer identity and key required" }
        require(allowedKinds.isNotEmpty()) { "issuer must have a bounded evidence role" }
    }
}

/** Host-owned lookup for immutable, previously recorded signed receipts. */
fun interface SignedSkillEvidenceIndex {
    fun find(receiptId: String): SignedSkillEvidence?
}

/** Host-owned blob read; production implementations should bound I/O before returning bytes. */
fun interface SkillEvidenceBlobStore {
    fun load(evidenceRef: String): ByteArray?
}

/**
 * Versioned, unambiguous, domain-separated binary encoding. All fields MUST remain signed.
 * For format changes, define a new domain/version; never silently reinterpret older signatures.
 */
object SkillEvidenceCanonicalCodec {
    fun payload(envelope: SignedSkillEvidence): ByteArray {
        val r = envelope.receipt
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            fun field(text: String) {
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            field("R52-SIGNED-EVIDENCE/1:ed25519")
            field(envelope.audience)
            field(envelope.sourceCommitSha)
            field(r.receiptId)
            field(r.skillId)
            field(r.version.major.toString())
            field(r.version.minor.toString())
            field(r.version.patch.toString())
            field(r.artifactSha256)
            field(r.targetState.name)
            field(r.kind.name)
            field(r.evidenceRef)
            field(r.issuerId)
            field(r.regressionFailures.toString())
            field(envelope.evidenceSha256)
            field(envelope.issuedAtEpochSeconds.toString())
            field(envelope.expiresAtEpochSeconds.toString())
        }
        return out.toByteArray()
    }
}

/**
 * JVM-17 Ed25519 verifier, fail-closed on unknown issuer, wrong role, bad signature, missing
 * evidence bytes, audience mismatch, expiry, or unavailable JCA provider.
 *
 * Actual ground-truth independence is a HOST POLICY decision expressed through pinned trust
 * roots; neither a cryptographic signature nor this class verifies scientific correctness.
 * No keys, receipts or blobs are persisted by this component (single canonical host store).
 */
class PinnedEd25519EvidenceAuthority(
    private val expectedAudience: String,
    private val expectedSourceCommitSha: String,
    issuers: Collection<TrustedEvidenceIssuer>,
    private val signedEvidenceIndex: SignedSkillEvidenceIndex,
    private val evidenceStore: SkillEvidenceBlobStore,
    private val clock: Clock = Clock.systemUTC(),
    private val maxValiditySeconds: Long = 3_600,
    private val maxEvidenceBytes: Int = 1_048_576,
    private val maxSignedPayloadBytes: Int = 65_536,
) : SkillEvidenceAuthority {
    private data class Pin(val policy: TrustedEvidenceIssuer, val publicKey: PublicKey)
    private val pins: Map<String, Pin>

    init {
        require(expectedAudience.isNotBlank()) { "expected audience required" }
        require(expectedSourceCommitSha.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) { "expected source commit required" }
        require(maxValiditySeconds > 0 && maxEvidenceBytes > 0 && maxSignedPayloadBytes > 0) { "limits required" }
        val provided = issuers.toList()
        require(provided.map { it.issuerId }.distinct().size == provided.size) { "duplicate issuer identity" }
        pins = provided.associate { policy ->
            val keyBytes = Base64.getDecoder().decode(policy.publicKeyX509Base64)
            val publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            policy.issuerId to Pin(policy, publicKey)
        }
    }

    override fun authenticate(receipt: SkillReceipt): Boolean {
        return try {
            val signed = signedEvidenceIndex.find(receipt.receiptId) ?: return false
            signed.receipt == receipt && authenticateEnvelope(signed)
        } catch (_: Exception) {
            false
        }
    }

    /** Verify THIS exact immutable envelope, not merely another record with the same receipt ID. */
    fun authenticateEnvelope(signed: SignedSkillEvidence): Boolean {
        return try {
        if (signedEvidenceIndex.find(signed.receipt.receiptId) != signed) return false
        val receipt = signed.receipt
        if (signed.audience != expectedAudience ||
            signed.sourceCommitSha != expectedSourceCommitSha) return false
        val pin = pins[receipt.issuerId] ?: return false
        if (receipt.kind !in pin.policy.allowedKinds) return false
        if (receipt.kind == SkillReceiptKind.EXTERNAL_GROUND_TRUTH && !pin.policy.independentGroundTruth) return false
        val now = clock.instant().epochSecond
        if (signed.issuedAtEpochSeconds > now + 60L || signed.expiresAtEpochSeconds <= now) return false
        if (signed.expiresAtEpochSeconds - signed.issuedAtEpochSeconds > maxValiditySeconds) return false
        val canonical = SkillEvidenceCanonicalCodec.payload(signed)
        if (canonical.size > maxSignedPayloadBytes) return false
        val signature = Base64.getDecoder().decode(signed.signatureBase64)
        if (signature.size != 64) return false
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(pin.publicKey)
        verifier.update(canonical)
        if (!verifier.verify(signature)) return false
        val evidenceBytes = evidenceStore.load(receipt.evidenceRef) ?: return false
        if (evidenceBytes.size > maxEvidenceBytes) return false
        val expectedDigest = signed.evidenceSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(evidenceBytes), expectedDigest)
        } catch (_: Exception) {
            false
        }
    }
}
