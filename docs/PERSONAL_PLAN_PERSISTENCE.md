# Personal plan authority: repository increment

The existing pure replacement decision now runs inside the occurrence database transaction.
Schema 5 preserves schema 1–4 data and adds append-only plan commits and retirements.
A stale expected version returns Conflict. Retrying the same active revision with the current
version is a no-op; changing its bound payload or restoring a retired revision is rejected.

Replacement retains old occurrences, execution journals and scheduling attempts, including
attempts without a receipt. The delivery gate, future scheduling selection and action
transaction reject retired occurrences. Reconciliation recognizes this monotonic revocation
without deleting evidence or claiming platform cancellation succeeded.

Restore quarantine cannot be removed implicitly by replacing a plan. Exports use
R52BKUP2 and include the existing plan commits and retirement tables. Legacy R52BKUP1
imports create no plan authority. A restore requires an empty destination and commits
all seven tables, fresh delivery epoch and quarantine together; a failure rolls back all.
This repository API is deliberately not connected to personal UI/receiver/service flows.

## Backup 2 contract and limits

The envelope keeps bounded UTF-8, fixed fields, an 8 MiB limit and a SHA-256 damage
checksum. Version and magic must agree. Old apps reject format 2; no silent downgrade
is offered. Occurrence snapshots, action replay and scheduling evidence retain their
existing validation. Plan versions must be contiguous per event, revision identities
unique, fingerprints well-formed and times valid. Retirement references must resolve
to the same personal event and to an untouched scheduled occurrence after the recorded
cutoff. Empty plans are valid. No SQL, activation permission or source installation
epoch is imported. Every open restored occurrence is quarantined, including retirements;
the retirement fence independently remains permanent.

Historical fingerprints are preserved as opaque bindings. Schema 5 did not persist
candidate membership: a legacy due occurrence may share the adopted plan revision
while being omitted from that commit's candidate set. Reconstructing the fingerprint
from all same-revision rows would wrongly reject valid data. This format therefore does
not prove candidate membership or the authenticity/completeness of a deliberately
rewritten history. Checksums are not signatures. Imported authority must not authorize
execution without the separate activation validation. Gate 3 remains a host-tested
candidate pending that limitation's coordinator review; later activation gates remain open.

## Next work before product activation

1. Resolve backup authority completeness/membership admission without inventing history.
2. Fault matrix and database concurrency fencing (52-gate plan, gates 4–5).
3. Explicit personal restore activation with epoch and authority admission checks (gate 6).
4. Personal receiver/service integration, rechecking authority at the actual effect.
5. Fresh release candidate and later device acceptance only at gate 52 of the current plan.

Validation is local Robolectric with native SQLite. See the run's test-results.json and
tests.log for actual outcomes. No real-device, deployment, or CI success is implied.
