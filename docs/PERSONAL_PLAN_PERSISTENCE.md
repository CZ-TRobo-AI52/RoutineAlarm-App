# Personal plan authority: repository increment

The existing pure replacement decision now runs inside the occurrence database transaction.
Schema 5 preserves schema 1–4 data and adds append-only plan commits and retirements.
A stale expected version returns Conflict. Retrying the same active revision with the current
version is a no-op; changing its bound payload or restoring a retired revision is rejected.

Replacement retains old occurrences, execution journals and scheduling attempts, including
attempts without a receipt. The delivery gate, future scheduling selection and action
transaction reject retired occurrences. Reconciliation recognizes this monotonic revocation
without deleting evidence or claiming platform cancellation succeeded.

Restore quarantine cannot be removed implicitly by replacing a plan. Backup format 1
cannot carry the new authority: export and import refuse stores with plan commits.
Existing stores without plan commits keep their existing backup behavior.
This repository API is deliberately not connected to personal UI/receiver/service flows.

## Next work before product activation

1. Versioned portable backup including plan commits and retirements, strict referential
   validation, import rollback, and backwards compatibility with format 1.
2. Explicit personal restore activation with epoch and authority admission checks.
3. Approved personal receiver/service integration, rechecking authority at the actual effect.
4. Independent review, fresh APK build, and device acceptance for that exact APK.

Validation is local Robolectric with native SQLite. See the run's test-results.json and
tests.log for actual outcomes. No real-device, deployment, or CI success is implied.
