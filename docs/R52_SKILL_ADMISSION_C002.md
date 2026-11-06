# R52 Skill Evolution C002 — Admission / Trust Boundary

**Cycle:** `R52-RCEA-2026-11-02-C002`
**Parent artifact:** `R52_ALPHA14_SKILL_INTELLIGENCE_CORE.patch` (SHA256 `5fd799b0a43f1c05f8c874b8b8b478361d90217f9c1bca28a6c0ac3faded1158`).
**Observed remote target:** `CZ-TRobo-AI52/RoutineAlarm-App`, `codex/routinealarm-alpha14-integration`, HEAD `fd1f5925a6c54a4dd5ef1420b26e3f0f23929e36`.
**Prerequisite:** The parent C001 Kotlin patch was not written to the remote repository (write permission 403); this C002 deliverable is a consolidated successor, not a separate conflicting fork.

## Highest-ROI failure found

The original registry unconditionally stored `SkillDescriptor(state = VERIFIED)` without evidence and the router accepted it as a verified active skill. Raw counts could also yield allowed `VERIFIED` checks and positive `validatedUtility()`. These violate the project lifecycle/evidence invariant even if local tests passed.

## Implemented delta

1. **No direct active-state registration:** only `PROPOSED` may be registered; a version replacement is lineage-bound to the exact prior version and resets the state.
2. **Content-addressed receipts:** SHA-256 content identity excludes lifecycle state, but includes immutable descriptor fields and provenance.
3. **Real state transitions:** `SkillRegistry.promote` accepts only one adjacent transition with exact skill ID/version/hash/stage/kind, zero reported regressions and an authority-validated receipt.
4. **Fail-closed host adapter:** absent/throwing evidence-verifier never promotes. Fixture verifier is testing only; the real Forge evidence issuer is unconnected.
5. **Anti-replay:** receipt ID collision refused, exact current-stage retry idempotent, stale replay refused.
6. **Metric trust boundary:** unauthenticated counts never authorize `VERIFIED` or positive `validatedUtility`; estimated utility is advisory. Nonfinite gains and costs are rejected.

## Explicit exclusions

- No forged notion of living/conscious/superintelligent status.
- No claim of authentic production attestation or independent ground truth.
- No new SQLite store, cloud deployment, runtime authority, Android UI consumer or automatic modification of user routines.
- No claim of full Android Gradle or device test.

## Paired baseline / candidate evidence (local fixed adversarial cases)

The exact same Kotlin benchmark source was compiled once against the preserved **C001** source and once against the **C002** source. It checks five security invariants shared by both APIs: registration-stage forgery, missing lineage, self-reported VERIFIED counts, self-reported validated utility and NaN ROI admission.

| Fixed contract cases | C001 baseline | C002 candidate |
| --- | ---: | ---: |
| Expected unsafe input rejected | **0/5** | **5/5** |

This proves only that the candidate rejects these five known, fixed, synthetic bypass cases. It is **not** an independent holdout, a production penetration test or proof of real capability/AGI gain. The previously available local smoke suite continues passing, and newly implemented authenticated-transition behavior is exercised through explicitly synthetic verifier fixtures.

## Reproducibility

Run Kotlin smoke tests from repository root (after applying consolidated patch):

```bash
kotlinc app/src/main/java/com/cztr/routinealarm/r52/skill/*.kt \
  local-verification/ContractSmoke.kt -include-runtime -d /tmp/r52-c002-legacy.jar
java -jar /tmp/r52-c002-legacy.jar
```

`local-verification/` is supplied in the accompanying ZIP, **not** installed as an Android source directory. `app/src/test/...` contains actual JUnit4 tests for the repository build. Locally, the JUnit-style sources are exercised through a minimal host shim to prove behavioral contracts and compilability; this does not stand in for the project's Gradle JUnit/Android execution.

After GitHub write access is restored, run `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --continue --stacktrace`, then collect CI commit/run ID and release/devices receipts before advancing status.

## State and single next action

**Implemented/tested:** yes, locally, with synthetic verifier fixtures and real Kotlin code. **Benchmarked:** paired five-case local synthetic contract comparison only; no objective model performance comparison. **Deployed/Verified:** no. **Next highest-ROI action:** bind `SkillEvidenceAuthority` to an authenticated, independently verifiable receipt from the existing Forge outcome-evidence authority, preserving one canonical store and denying any status elevation on missing/failing evidence.
