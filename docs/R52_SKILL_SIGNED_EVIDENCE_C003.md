# R52 Skill Evolution C003 — Pinned Signed Evidence Authority

Status: **IMPLEMENTED / TESTED_LOCAL**. Neither deployed nor independently externally verified.

## Why

C002 blocked direct registration as VERIFIED and required a host-owned
`SkillEvidenceAuthority`, but did not provide a real authenticated implementation.
An insecure authority such as `SkillEvidenceAuthority { true }` could still approve
fabricated receipts. C003 supplies a fail-closed, public-key-pinned Ed25519
verifier as an optional host adapter to C002, without adding a second store.

## Inputs and security boundary

Production input ports are:

- `expectedAudience`: exact repository + branch or environment scope decided by host, never by an incoming receipt.
- `TrustedEvidenceIssuer`: pinned X.509 Ed25519 public key and strictly limited `allowedKinds`, provisioned by host configuration. **Never ship private signing keys in the app or artifacts.**
- `SignedSkillEvidenceIndex`: host's immutable record lookup by receipt ID.
- `SkillEvidenceBlobStore`: host's independently stored evidence bytes by `evidenceRef`. The host must bound I/O and accept only sanctioned references.
- `Clock`: UTC clock; injectable for deterministic evaluation.

The authenticated signed payload contains every original C002 `SkillReceipt` field,
including SHA-256 skill fingerprint and regression count, plus the exact audience, immutable source commit SHA,
SHA-256 of external evidence bytes, issued-at and expires-at epochs. Canonical
encoding: `R52-SIGNED-EVIDENCE/1:ed25519` followed by explicit big-endian
32-bit UTF-8 length-prefixed fields. No user-controlled string concatenation
or unsigned metadata is used.

The verifier **denies** absent / duplicated / malformed trust pins, issuer or
kind mismatch, wrong audience, altered receipt, absent proof, altered proof,
invalid signature, expired/future/overlong validity, oversize payload, or
resolver/provider exceptions. No exceptions promote the registry. The
certificate/key is not learned from the untrusted record.

For `EXTERNAL_GROUND_TRUTH`, the pinned issuer must additionally have
`independentGroundTruth=true` set by the **trusted host**. The host may not grant
this role to the same builder that created the skill. A cryptographic signature
confirms only *which key attested what*; it does not prove benchmark validity,
independent methodology, scientific correctness, or the absence of data leakage.
An independent evaluator with retained raw results and holdout policy remains
required before production use of the `VERIFIED` state.

## Suggested integration (host side, not a second database)

```kotlin
val authority = PinnedEd25519EvidenceAuthority(
    expectedAudience = "github:CZ-TRobo-AI52/RoutineAlarm-App@codex/routinealarm-alpha14-integration",
    expectedSourceCommitSha = "fd1f5925a6c54a4dd5ef1420b26e3f0f23929e36",
    issuers = listOf(
        TrustedEvidenceIssuer(
            issuerId = "forge-build-issuer",
            publicKeyX509Base64 = buildKeyFromTrustedConfiguration,
            allowedKinds = setOf(
                SkillReceiptKind.IMPLEMENTATION,
                SkillReceiptKind.TEST_PASS,
                SkillReceiptKind.BENCHMARK_PASS,
                SkillReceiptKind.DEPLOYMENT,
            ),
            independentGroundTruth = false,
        ),
        TrustedEvidenceIssuer(
            issuerId = "independent-holdout-evaluator",
            publicKeyX509Base64 = holdoutKeyFromTrustedConfiguration,
            allowedKinds = setOf(SkillReceiptKind.EXTERNAL_GROUND_TRUTH),
            independentGroundTruth = true,
        ),
    ),
    signedEvidenceIndex = SignedSkillEvidenceIndex { receiptId -> canonicalStore.findSignedReceipt(receiptId) },
    evidenceStore = SkillEvidenceBlobStore { ref -> canonicalStore.readImmutableEvidenceBytes(ref) },
)
val registry = SkillRegistry(authority)
```

The example shows a **required integration boundary**, not an actual deployed
Forge issuer. The current project does not contain a verified `canonicalStore`
implementation or suitable pinned public keys for this adapter. The default
`SkillRegistry()` remains deny-all.

## Compatibility and runtime

- Kotlin/JVM, Android module compiles against Java 17, no added dependencies.
- Ed25519 JCA support is required at runtime. It is standard on JDK 17, but
  availability on all Android API 26+ devices has **not** been established.
  Prefer server-side/Forge validation. Unsupported providers deny access;
  do not silently fall back to RSA/weak signature algorithms.
- C002 `SkillReceipt`, lifecycle, routing and `SkillRegistry.promote` API unchanged.
- C002 fingerprints stay stable; the new signature format is versioned separately.
- No self-modifying production trust store; trust rotation and revocation must
  be controlled by host configuration plus independent audit.

## Reproducible local evidence

`R52SkillSignedEvidenceTest` covers signature verification, role separation,
expired/future/overlong receipts, evidence-body hash binding, audience and exact-source-commit scoping,
invalid keys, malformed signatures, untrusted issuer, provider failures and
complete adjacent lifecycle progression (with **fixture-only** holdout keys).

Local Kotlin compiler + JUnit API shim test result: **35/35** tests across
three classes. This validates the code path, **not** Android Gradle, a real
Forge issuer, scientific external ground truth, persistence/recovery or product
runtime. No production status promotion is claimed.

## Higher-ROI follow-up

Connect this adapter on a Forge server process with real host-owned receipts,
immutable evidence storage, scoped signer identities and independent holdout
attestation; run actual CI, revocation/key-rotation and cold-restart replay tests.
Do not embed production private keys in RoutineAlarm or commit secrets.
