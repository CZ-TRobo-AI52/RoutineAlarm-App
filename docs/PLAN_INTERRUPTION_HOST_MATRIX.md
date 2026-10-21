# Gate4: host interruption boundaries

Baseline: 8ac7c919aba5247825fa12944e0160d178059730. Scope frozen before execution.

1. Replacement after committed attempt: no platform call, stale NOT_CALLED receipt, old plan retired after reopen.
2. Replacement after simulated platform effect then lost acknowledgement: unknown attempt remains, old token rejected, cancellation exception can retry, late scheduling receipt cannot revive retired plan.
3. Lost replacement acknowledgement: stale expected version conflicts; reread and identical retry neither duplicates nor changes bytes.
4. Lost restore acknowledgement: occupied-store retry fails without changing committed epoch, quarantine or data.

These characterize existing code through native SQLite/Robolectric and existing callbacks. They do not simulate abrupt OS kill, power loss, concurrent threads, real AlarmManager, or device audio. No production behavior changed. Gate4 remains partial; internal SQL crash points, checkpoint and personal activation matrix still open.

Gate3 ruling: historical format2 preservation has tests; original candidate membership is not stored in schema5, so authenticated/membership admission stays BLOCKED_WITH_EVIDENCE and personal activation stays disabled. Independent review agrees with this narrower contract. No format change in this run.
