# R52 Skill Evolution C005 — VERIFIED admission is a typed holdout gate

**State:** IMPLEMENTED / TESTED_LOCAL only. Not remotely committed, not Android-CI tested, not deployed, not independently verified. Patch based on C004 and Alpha14 HEAD `fd1f5925a6c54a4dd5ef1420b26e3f0f23929e36`.

## Baseline defect (confirmed in C004)

C004's `SkillRegistry.promote` performed receipt signature, role and blob-hash checks but did not interpret `EXTERNAL_GROUND_TRUTH` bytes before changing state to `VERIFIED`. C004 tests deliberately registered and promoted **signed arbitrary JSON**, then relied on the downstream improvement selector to reject the malformed holdout. Other consumers of `VERIFIED` could therefore mistake a signature over arbitrary data for a typed external benchmark.

## C005 implementation

- The registry's constructor now takes a `VerifiedAdmissionPolicy`. The **default** is `MissingVerifiedAdmissionPolicy`, which refuses `VERIFIED` even when the receipt has a valid signature. Earlier lifecycle transitions still use C003's pinned receipt authority and unchanged rules.
- `PinnedHoldoutVerifiedAdmission` is the explicit, trusted host configuration for `VERIFIED`. It requires the exact registry receipt, current Ed25519 signature, pinned audience and commit, independent issuer role, immutable-index recheck, evidence SHA-256, canonical `R52-PAIRED-HOLDOUT/1` parsing, preregistered evaluation ID, dataset fingerprint, baseline fingerprint, host minimum sample size, and positive one-sided 95% Hoeffding lower bound. No condition can be selected by the skill candidate.
- The admission policy and C004 `EvidenceBoundImprovementSelector` now reuse `PinnedHoldoutProofVerifier` and `SkillHoldoutConfidence`. This avoids drift between lifecycle acceptance and downstream selection.
- The original 52 local tests remain intact as *behavioral tests*. Three older tests deliberately use a fixture-only admission policy so they can continue testing their narrower C002/C003/C004 components (signatures, selector defenses) with intentionally malformed evidence. **Such fixture policies must never be configured in production.**
- No persistent evidence store was added. The Forge host is still responsible for signed-index custody, benchmark preregistration, key control, audit, revocation and recovery. The Android product runtime does not call the new core.

## Security boundary and limits

1. A `VERIFIED` lifecycle transition is no longer granted by `SkillEvidenceAuthority.authenticate(receipt)` alone.
2. A signed but malformed or wrong-scope proof leaves the skill in `DEPLOYED`, with no `verifiedReceiptFor` value.
3. Duplicate exact receipt replay is idempotent; old accepted verification receipts do not carry across a new skill version.
4. A host that intentionally provides an `admit { true }` policy, shares issuer private keys, or allows skill candidates to configure benchmark pins defeats the host trust boundary. This cannot be prevented by an in-process Kotlin interface. In production, expose only a controlled factory and keep signing/verification isolated from skill code.
5. Cryptographic authentication does not prove holdout independence, correct labels, statistical calibration, unbiased sampling or absence of benchmark leakage. This is *not* an external independent validation result. The one-sided Hoeffding bound assumes independently sampled paired observations; it does not automatically correct for repeated adaptive testing or many candidates.
6. Existing persisted `VERIFIED` records from an older version, if any, require re-admission or quarantine during future durable-store migration. This patch does not introduce a migration or persistent store.
7. `RecursiveImprovementKernel.chooseBest` remains **advisory only**; trusted selection requires C004's signed selection API.

## Verification protocol and evidence

- Reproduced C004 baseline: 52/52 Kotlin local tests before editing; existing test permits malformed signed JSON to be promoted in fixture mode.
- RED: a new 9-case `R52VerifiedAdmissionBoundaryTest` did not compile before C005 symbols were added; compile failure due to missing admission contract and policy.
- GREEN: 61/61 pure Kotlin/JVM tests with `kotlinc -Werror` and the existing local JUnit-compatible harness; 52 prior + 9 new. New tests cover default deny, signed positive holdout, arbitrary signed JSON refusal, mismatched benchmark pins, sample floor and positive lower bound, evidence mutation, missing index, thrown policy, and version replay.
- Security mutation checks, complete post-packaging runs and actual artifact digests are recorded in `RUN_DELTA_C005.json` and `evidence/`.
- Full Android Gradle `testDebugUnitTest`, `lintDebug`, `assembleDebug`, official CI, real signed independent Forge holdout, and production device testing remain **NOT RUN**.

## Rollback and integration sequence

Apply either the C005 consolidated patch to the exact original Alpha14 base, **or** apply only C005 incremental patch to the exact C004 source. Never stack the consolidated and incremental patches.

Run Gradle tests and CI before publishing. Set up a host-owned, preregistered, independent holdout issuer before configuring `PinnedHoldoutVerifiedAdmission`. Without it the default is intentionally fail-closed, not a partial `VERIFIED` implementation.

## Highest-ROI next action

Restore GitHub contents-write permission, integrate C005 on an isolated branch pinned to the verified Alpha14 base, run full Android Gradle CI, then bind an independent durable Forge signed-index/holdout evaluator to the new `VERIFIED` admission port. Add rejection/revocation and out-of-distribution evaluation before real production promotion.
