# R52 Typed VERIFIED Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. All steps are test-driven and checkable.

**Goal:** Prevent any `VERIFIED` lifecycle promotion based solely on a cryptographically valid receipt with untyped evidence bytes.

**Architecture:** Require an injected fail-closed `VerifiedAdmissionPolicy` at `SkillRegistry.promote` and reuse a shared pinned holdout proof verifier across admission and authoritative selection. The host owns benchmark scope, trust roots, signed evidence index and store; no new database or Android runtime is introduced.

**Tech Stack:** Pure Kotlin JVM 17, JUnit 4-compatible local harness, existing Android Gradle module.

**Spec:** `docs/R52_SKILL_VERIFIED_ADMISSION_C005.md`; parent `docs/R52_SKILL_EVIDENCE_BOUND_SELECTION_C004.md`.

## Global Constraints

- No change to RoutineAlarm alarm, notification, occurrence, service or UI behavior.
- Preserve all 52 C004 contract tests; isolate deliberate fixture-only exceptions to test code.
- Never admit an unsigned or unverifiable proof; reject malformed, wrong-scope, stale or non-positive holdout evidence.
- Keep preregistration host-controlled and immutable for a verifier instance.
- No claim of deployment, scientific validity or independent external verification from local signatures.

## Review Focus

- Deliberately signed arbitrary JSON cannot grant VERIFIED.
- Replacing blob bytes between authority and policy check is rejected.
- Already accepted old-version receipts cannot promote new versions.
- Default constructors fail closed even with valid receipts.
- Fixture-only policies must be visible and excluded from production configuration.

### Task 1: Reproduce the old admission bypass

**Files:** Create `app/src/test/java/com/cztr/routinealarm/r52/skill/R52VerifiedAdmissionBoundaryTest.kt`.

- [x] Write failing contract tests for default admission, typed holdout, wrong scope, sample threshold and replay.
- [x] Observe missing C005 interfaces / expected RED compile failure.

### Task 2: Implement shared admission/verifier

**Files:** Create `app/src/main/java/com/cztr/routinealarm/r52/skill/VerifiedHoldoutAdmission.kt`; modify `SkillRegistry.kt`, `EvidenceBoundImprovementSelection.kt`.

- [x] Implement default-deny `VerifiedAdmissionPolicy` and enforce it before writing lifecycle state.
- [x] Implement pinned holdout verification with common C004 proof checks.
- [x] Reuse identical confidence bound in both admission and selection.

### Task 3: Preserve narrower existing tests

**Files:** Modify C002/C003/C004 test suites to use an explicit fixture policy only where signed malformed/non-holdout test inputs were intentional.

- [x] Run Kotlin compilation with `-Werror` and all 52 old + nine new cases.
- [x] Verify actual output and check that failed mutations are detected.

### Task 4: Package and hand off without promotion

**Files:** Add this plan and `docs/R52_SKILL_VERIFIED_ADMISSION_C005.md`; update `docs/ALPHA14.md`.

- [x] Produce incremental and consolidated patches, manifest, checksums and reproducible ZIP.
- [x] Verify clean patch application and rerun local tests from the consolidated result.
- [x] Report GitHub write ability, full Android CI and external ground truth independently.
