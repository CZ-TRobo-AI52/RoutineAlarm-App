# R52 Skill Intelligence Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate a deterministic, rollbackable R52 Skill Intelligence foundation into RoutineAlarm Alpha14 without introducing a second persistence engine or altering existing alarm execution semantics.

**Architecture:** Add a pure-Kotlin `r52.skill` core with explicit lifecycle/provenance models, an in-memory registry, deterministic routing/composition, evidence-aware promotion gates, and a recursive improvement ROI kernel. Keep Android/UI/SQLite consumers outside this first core so the subsystem can be verified independently and adopted incrementally.

**Tech Stack:** Kotlin/JVM 17, JUnit 4, existing Android Gradle module.

**Spec:** `docs/ALPHA14.md` and `docs/R52_SKILL_INTELLIGENCE.md`.

## Global Constraints

- Preserve existing RoutineAlarm alarm, occurrence, recovery, notification and training behavior.
- Do not create a second persistent store in this slice.
- Maintain lifecycle states exactly: `PROPOSED -> IMPLEMENTED -> TESTED -> BENCHMARKED -> DEPLOYED -> VERIFIED`.
- No state jump without gate evidence.
- Verification requires external/ground-truth evidence, not only an internal score.
- Recursive improvement stops when expected marginal gain is not greater than cost + risk + complexity debt.
- Prefer deterministic, side-effect-free core logic.
- Every production behavior is introduced test-first.
- Keep all changes reversible by branch/commit.

## Review Focus

- Older skill versions must never silently replace newer versions.
- Negative triggers must veto otherwise-positive routing matches.
- Composition must cover required capabilities without duplicating a skill.
- VERIFIED promotion must fail without external ground-truth evidence.
- ROI selection must prefer validated net value over raw capability gain.

## Tasks

- [x] RED: create contract tests/harness and observe unresolved-reference compile failure before production code.
- [x] Implement lifecycle, provenance and monotonic registry.
- [x] Implement deterministic routing and composition.
- [x] Implement evidence-aware promotion and anti-Goodhart evaluation.
- [x] Implement recursive improvement ROI kernel and stop condition.
- [x] Add R52SkillEngine facade.
- [x] Compile pure-Kotlin core and run 11 local contract checks.
- [ ] Apply patch to `codex/routinealarm-alpha14-integration` (blocked by GitHub connector 403 in the originating ChatGPT run).
- [ ] Run repository Gradle unit tests, lint and debug assembly in GitHub Actions.
- [ ] Benchmark against no-skill / prior routing baseline.
- [ ] Connect first real Alpha14 consumer only after repository verification.
