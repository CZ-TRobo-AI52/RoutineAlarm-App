# RoutineAlarm Alpha14 UI/UX – Visual and Accessibility Acceptance Matrix

## Purpose

This matrix is a bounded screenshot/TalkBack plan for the cumulative Alpha14 UI candidate. It is
not proof of device behavior by itself. Goldens must be generated only from the exact candidate
commit that passed the Android unit suite, lint, assemble and fresh-source build.

## Dimensions

- Surfaces: Home, Plan, Studio, History, System, regular Alarm, Backup/Restore, TestAlarm/History.
- Themes: Light and Dark for every surface.
- Widths: 320dp, 360dp, 411dp and 600dp.
- Font scale: 100%, 130% and 200%.
- Evidence states: loading, unavailable, paused, permission-required, unknown/recovery, active,
  busy, invalid and restore-quarantined where supported.

The executable catalog is `VisualScenarioCatalog.kt`. It contains 32 pairwise scenarios rather than
an unbounded Cartesian product.

## Golden acceptance

For every scenario:

1. No clipped primary action.
2. No overlapping text or controls.
3. All actions remain reachable by scroll.
4. Minimum shared touch target remains 48dp.
5. Selected navigation/filter/theme/day state is conveyed by semantics, not color only.
6. Headings remain discoverable by accessibility traversal.
7. Unknown, unavailable and unverified states are not presented as success.
8. Stored trigger state is not presented as audible delivery.
9. Screen close, output close, Done, Skip and Snooze remain visibly distinct.
10. Screenshot filename equals `VisualScenario.snapshotName`.

## TalkBack device pass

After exact-commit CI and APK packaging, verify on the actual device:

- traversal order follows visual order;
- selected bottom destination, day, filter and theme are announced;
- headings can be navigated;
- buttons announce their full labels at 200% font;
- lock-screen alarm actions remain reachable;
- no action fires while merely exploring with TalkBack;
- dark/light/system theme remains after process restart.

Record device model, Android build, APK SHA256, scenario ID, observed result and PASS/FAIL. A host
screenshot pass is not a device or TalkBack verification.
