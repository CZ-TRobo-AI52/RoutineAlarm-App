# R52 Skill Evolution C004 — Evidence-Bound Improvement Selection

**Status:** IMPLEMENTED and TESTED_LOCAL. Not deployed, not remotely committed, not independently VERIFIED. This is an additive, opt-in service-side/core component: the production Android alarm runtime does not call it.

## Why this cycle was selected

C003 cryptographically authenticates a skill's promotion receipt, but `ImprovementCandidate(validated=true)` and `SkillEvalResult.verifiedCapabilityGain` remain **caller-supplied advisory inputs**. Treating those fields as proof would allow the improvement selector to optimize fabricated success claims, even while the lifecycle gate was cryptographically protected. C004 closes this decision boundary by requiring proof-backed, registry-accepted, currently authenticated external holdout results for *non-advisory* selection.

## New integration path

Use `R52SkillEngine.selectEvidenceBoundImprovements(skillIds, request, authority, signedIndex, evidenceStore)`. The engine uses its **existing** `SkillRegistry`, not an independent second lifecycle authority or persistence layer. The selection logic rejects a skill unless it is currently VERIFIED and `verifiedReceiptFor(skillId)` returns the exact ground-truth receipt accepted during its lifecycle. The receipt must still pass the pinned C003 Ed25519 verifier *at decision time*, with the exact same immutable signed envelope in both the selector and authority's index. The proof bytes are checked a second time against the signed SHA-256 after loading, protecting against changes between authentication and evaluation.

`EvidenceBoundSelectionRequest` is **host-owned and preregistered**: `evaluationId`, `datasetSha256`, `baselineArtifactSha256`, and the minimum sample size. A caller may select skill IDs to compare but cannot choose a friendlier benchmark or substitute a baseline. Unsupported scopes, unsigned data, wrong signer roles, expired or missing evidence, malformed proofs, mutations, replays after a skill version change, and provider exceptions fail closed.

## Holdout wire format: `R52-PAIRED-HOLDOUT/1`

Binary, unambiguous, big-endian fields:

1. UTF-8 32-bit-length-prefixed domain string `R52-PAIRED-HOLDOUT/1`;
2. similarly encoded `evaluationId`, `datasetSha256`, `baselineArtifactSha256`;
3. three signed 32-bit counters: `wins`, `losses`, `ties` over **paired comparisons on the same holdout tasks**;
4. five signed 64-bit nonnegative resource quantities: `tokenCost`, `latencyMs`, `computeMicrounits`, `complexityMicrounits`, `riskMicrounits`.

A valid record has 1–1,000,000 observations and no trailing bytes; decoding is limited to 4,096 bytes, UTF-8 is strict, and all numeric values are validated. The entire evidence blob is SHA-256-bound to a C003 Ed25519-signed receipt. The issuer attests these quantities. Their actual measurement independence and correctness must be enforced by the separate evaluator.

## Decision metric

For each candidate, with N paired tasks, `d=(wins-losses)/N`. The conservative one-sided lower confidence bound is:

`d_lower = d - sqrt(2 * ln(20) / N)`

(the Hoeffding bound for independent paired outcomes in [-1,1], at one-sided alpha 0.05). If N is below the host minimum or `d_lower <= 0`, the candidate is excluded. Qualified candidates rank by:

`evidenceBoundRoi = d_lower / (1 + tokens/1000 + latency_ms/1000 + compute_micro/1e6 + complexity_micro/1e6 + risk_micro/1e6)`

These are **policy-defined utility weights**, not a claim of economically calibrated units or scientific certainty. Deterministic tie-breaking is used. The decision carries `skillId`, `version`, `receiptId`, `evidenceSha256`, sample size, observed gain, lower bound, and ROI, enabling provenance and later failure mining.

## Security and statistical limitations

- A signature proves *which host-authorized key attested what*; it does not prove true task outcomes, random sampling, no leakage, calibration, or evaluator independence. The independent signer must be separately controlled and its holdout procedure auditable.
- `VERIFIED` in the legacy registry can still be reached with an arbitrary signed `EXTERNAL_GROUND_TRUTH` blob. C004's selector refuses to interpret any blob without the new canonical schema and matching preregistered benchmark. A future change should move semantic holdout validation into a server-side admission policy before the `VERIFIED` lifecycle transition.
- The standalone legacy `RecursiveImprovementKernel.chooseBest(ImprovementCandidate)` stays **advisory only** for compatibility. It must never be called for privileged production promotion, deployment or self-modification. `validated=true` in that type has no authenticated meaning. The supported production selection path is the new engine method.
- This optional core is not connected to production Forge, permanent canonical storage or a real external holdout evaluator. There is no evidence that Android API 26 provides Ed25519 JCA; run this verifier on an appropriately controlled JVM/Forge host.
- Frozen host policy, least-privilege keys, audit trail, revocation, secure durable recovery, and independent holdout validation remain deployment requirements. There are no private signing keys, secrets, new databases or Android alarms in this patch.

## Verification, falsification and recovery

- C003 baseline: 35/35 local Kotlin JUnit-compatible tests passed before editing.
- C004 Red: missing `EvidenceBoundImprovementSelector` and `verifiedReceiptFor` symbols caused the expected compile failure.
- C004 Green: **52/52** local Kotlin contract tests (`kotlinc -Werror` + JVM), including **17 new C004 tests** and 35 old tests.
- Separate signers for implementation and independent ground truth exercised with generated *fixture-only* Ed25519 keys.
- 5 deliberate security mutations were detected by the tests as runtime failures: signature-authentication bypass, benchmark-scope bypass, confidence-bound bypass, sample-floor bypass and second evidence-hash bypass.
- No Gradle Android CI, physical-device testing, real Forge proof, signer provisioning, or production deployment has been performed.
- To roll back, revert the C004 incremental Git patch. C003 behavior and contracts remain intact.

## Next highest-ROI action

Obtain GitHub contents-write permission for `CZ-TRobo-AI52/RoutineAlarm-App` (branch creation still receives HTTP 403); apply the **C004 cumulative patch only** to the exact C003 target base commit `fd1f5925a6c54a4dd5ef1420b26e3f0f23929e36`; run full Gradle CI; connect a separately controlled, durable Forge holdout signer and preregistered benchmark authority. Until that is done, the C004 engine method is a tested integration port, not a deployed skill-evolution service.
