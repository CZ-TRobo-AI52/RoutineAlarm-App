# R52 Evidence-Bound Improvement Selection Implementation Plan

> **For agentic workers:** Apply `superpowers:executing-plans` or an equivalent test-first implementation flow. Preserve existing C003 contracts.

**Goal:** Replace unauthenticated `validated=true` *for privileged selection* with authenticated, independently attested holdout data, without changing the legacy advisory planner.

**Architecture:** Retain the C003 in-memory registry and Ed25519 trust adapter; add a strict binary holdout format, provenance-bearing evidence-bound ranking, explicit engine facade and fail-closed reauthentication. No new store or Android runtime integration.

**Tech Stack:** Kotlin/JVM 17, JUnit 4, Android project's existing Kotlin compilation.

**Spec:** `docs/R52_SKILL_EVIDENCE_BOUND_SELECTION_C004.md`.

## Global constraints

- C003 and earlier 35 local contracts must remain green.
- `VERIFIED` lifecycle status alone does not constitute benchmark provenance.
- Reuse the *accepted* `EXTERNAL_GROUND_TRUTH` receipt for the current version.
- No candidate-provided benchmark ID, baseline fingerprint or unsigned costs.
- Revalidate the exact signed envelope and proof bytes at use time.
- Fail closed on missing/changed backing evidence, scope mismatch and provider errors.
- Conservatively reject non-positive lower-bound gains and insufficient samples.
- Do not claim external verification or deployment from local fixture tests.

## Task 1 — Baseline and RED

- [x] Verify C003 bundle and record 35/35 result.
- [x] Write 13 RED selection tests; record unresolved references from the missing implementation.

## Task 2 — Evidence-bound selector

- [x] Add `SkillHoldoutObservation`, `SkillHoldoutEvidenceCodec` and `EvidenceBoundImprovementSelector`.
- [x] Add registry accessor for exactly accepted current-version VERIFIED receipt.
- [x] Add snapshot-specific `PinnedEd25519EvidenceAuthority.authenticateEnvelope`.
- [x] Implement deterministic conservative paired-outcome ROI and fail-closed decoding.
- [x] Execute full Kotlin JUnit-compatible suite; all cases pass.

## Task 3 — Integration and adversarial regressions

- [x] Add single registry-backed call to `R52SkillEngine`.
- [x] Add tests for mismatched indexes, version rollover, API delegation and evidence TOCTOU.
- [x] Re-run all 52 tests after additions.
- [x] Verify that removal of critical security conditions fails tests.

## Task 4 — Delivery

- [x] Produce additive incremental and full cumulative Git patches, ZIP and SHA-256 manifest.
- [x] Verify clean apply and byte-for-byte correspondence of patched files.
- [ ] Push to GitHub: currently blocked by contents-write 403.
- [ ] Run Android Gradle CI and independent Forge holdout: not possible from this local patch alone.
