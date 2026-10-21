# Gate 4: SQL statement failure matrix

Baseline: 9752a3b37f1a24231e437b9035a62e39cc3e77f1. The contract was frozen before execution.

The tests use native SQLite and temporary abort triggers. Each case fails the first matching write in a table, with a BEFORE or AFTER row trigger:

- 10 plan replacement cases cover schedule revisions, occurrences, journal entries, plan commits and retirements. Existing retirement history must survive.
- 18 restore cases cover the seven backup tables, quarantine and the delivery epoch.
- 2 receipt cases preserve the committed attempt when recording its receipt fails.

Each failure must leave the pre-operation backup bytes unchanged, including after closing and reopening the repository. Authority, retirement history, epoch and quarantine retain their prior state. A subsequent retry must succeed. Restored personal occurrences remain inactive. A returned platform call does not prove that its external effect occurred.

Triggers are installed after the repository has validated and opened its database, then removed in a finally block before reopening. The exception must contain the injected marker; unrelated schema or open failures cannot pass a test. Short fixture filenames avoid long Robolectric paths on Windows.

Validation: 268 tests in 32 suites passed, with no failures, errors or skips. Lint reported 0 errors and 14 existing warnings. The debug APK has the same SHA256 as the delivered Alpha12 APK.

These tests establish normal transaction rollback after first-row statement errors. They do not establish behavior after process termination, power loss, later-row failures or competing writers. Gate 4 remains partial. Dedicated personal activation and device acceptance remain open. There is no production code or schema change in this increment.
