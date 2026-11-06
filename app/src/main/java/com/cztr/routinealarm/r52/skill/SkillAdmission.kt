package com.cztr.routinealarm.r52.skill

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Content identity is independent of the runtime lifecycle state. */
object SkillFingerprint {
    fun of(skill: SkillDescriptor): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        fun group(values: Collection<String>) {
            field(values.size.toString())
            values.sorted().forEach(::field)
        }
        field("R52-SKILL-SPEC/1")
        field(skill.id)
        field(skill.version.toString())
        field(skill.purpose)
        group(skill.positiveTriggers)
        group(skill.negativeTriggers)
        group(skill.capabilities)
        group(skill.dependencies)
        field(skill.provenance.parentVersion?.toString() ?: "")
        field(skill.provenance.hypothesisId ?: "")
        group(skill.provenance.evalIds)
        field(skill.provenance.changeReason)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

enum class SkillReceiptKind {
    IMPLEMENTATION,
    TEST_PASS,
    BENCHMARK_PASS,
    DEPLOYMENT,
    EXTERNAL_GROUND_TRUTH,
}

/** Caller-provided receipts are untrusted until the host's authority verifies them. */
data class SkillReceipt(
    val receiptId: String,
    val skillId: String,
    val version: SkillVersion,
    val artifactSha256: String,
    val targetState: SkillLifecycleState,
    val kind: SkillReceiptKind,
    val evidenceRef: String,
    val issuerId: String,
    val regressionFailures: Int = 0,
) {
    init {
        require(receiptId.isNotBlank() && skillId.isNotBlank()) { "receipt identity required" }
        require(artifactSha256.matches(Regex("[0-9a-f]{64}"))) { "artifact SHA-256 required" }
        require(evidenceRef.isNotBlank() && issuerId.isNotBlank()) { "evidence origin required" }
        require(regressionFailures >= 0) { "regressions cannot be negative" }
    }
}

/** Host-owned external evidence checker. The default deny-all authority is deliberately fail-closed. */
fun interface SkillEvidenceAuthority {
    fun authenticate(receipt: SkillReceipt): Boolean
}

internal object DenyAllSkillEvidence : SkillEvidenceAuthority {
    override fun authenticate(receipt: SkillReceipt): Boolean = false
}

internal fun requiredReceiptKind(to: SkillLifecycleState): SkillReceiptKind? = when (to) {
    SkillLifecycleState.PROPOSED -> null
    SkillLifecycleState.IMPLEMENTED -> SkillReceiptKind.IMPLEMENTATION
    SkillLifecycleState.TESTED -> SkillReceiptKind.TEST_PASS
    SkillLifecycleState.BENCHMARKED -> SkillReceiptKind.BENCHMARK_PASS
    SkillLifecycleState.DEPLOYED -> SkillReceiptKind.DEPLOYMENT
    SkillLifecycleState.VERIFIED -> SkillReceiptKind.EXTERNAL_GROUND_TRUTH
}
