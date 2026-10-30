# RoutineAlarm Alpha14 UI/UX – Architecture and Performance Budgets

## Purpose

These budgets prevent the UI layer from becoming a second scheduler, store, journal or recovery
engine. They are acceptance targets, not measurements already achieved on a physical device.

## Structural budgets

- Pure `*UiMapper.kt` files import no Android APIs.
- Screen views perform no SQLite, repository, AlarmManager, schedule-all or restore effects.
- Primary screen files remain at or below 180 lines until a measured exception is reviewed.
- Main-thread occurrence reads remain zero; existing `LatestUiLoader` lifecycle invalidation stays in place.
- Home displays at most five bounded upcoming projections.
- Test history remains paginated through the existing 20-row read model.
- Visual scenarios remain bounded to 24–64 pairwise cases rather than a Cartesian explosion.

## Owner-side measured budgets

Measure on the exact candidate after unit/lint/assemble/fresh-build green:

| Operation | Target | Evidence |
|---|---:|---|
| Pure Home/Plan/Studio/History/System mapper P95 | <= 5 ms | JVM benchmark, warmed and cold reported separately |
| Primary screen construction P95 | <= 50 ms host | Robolectric trace; not a device claim |
| Main-thread database operations | 0 | existing database-gate tests |
| User tap to local visual state change P95 | <= 100 ms device | instrumented device trace |
| Navigation tab switch P95 | <= 150 ms device | frame timeline |
| Janky frames in bounded navigation pass | < 5% | Android frame metrics |
| Background recovery work during UI navigation | no synchronous wait | trace + lifecycle regression |

## Memory and lifecycle

- Destroyed/stopped Activities receive no late reader result.
- Theme and navigation state survive process recreation without retaining Activity references.
- View callbacks do not own repositories or services.
- Alarm, Backup and TestAlarm remain vertically scrollable at 320dp / 200% font.

## Claim boundary

Passing static architecture tests does not prove device performance. Device budgets become
`MEASURED` only with the exact APK SHA, device model, Android build, trace and raw report.
