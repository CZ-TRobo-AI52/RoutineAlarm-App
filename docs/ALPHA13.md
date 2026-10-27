# Alpha13 development: restore admission foundation

Parent: published Alpha12 commit `6de6b68dab158fd918c20f14d117782fb43cd18b`.
Alpha12 release assets remain unchanged. This is development work, not an Alpha13 release.

## OS backup and device transfer

The weekly scheduling preference is excluded from legacy Auto Backup, cloud backup,
and device transfer. Other settings keep their previous backup policy; the database
exclusion remains intact. A platform backup agent resets weekly enablement after
restore completion, including an old payload containing `weekly_schedule_enabled=true`.
It writes synchronously and throws if persistence fails. It does not open the occurrence
database, create a new schedule, or start an output.

Normal same-device app updates do not invoke that restore-completion hook, so they
retain the existing weekly toggle. After a restore, the user can explicitly enable
the weekly schedule again through the existing UI. Test alarms and their journal,
delivery epoch, receipts and restore quarantine are not modified by the hook.

The implementation follows Android's [Auto Backup custom agent contract](https://developer.android.com/identity/data/autobackup#ImplementingBackupAgent)
with `fullBackupOnly=true` and the [restore-completion callback](https://developer.android.com/reference/android/app/backup/BackupAgent#onRestoreFinished()).

## Verification boundary

`OsRestoreAdmissionTest` invokes the actual manifest-selected agent and existing
receivers with Robolectric on API 28 and 31, real preferences and native SQLite.
It checks old-payload disablement, synchronous persistence, repeated completion,
boot/stale-intent suppression, ordinary upgrade, explicit re-enable and test-history
isolation. This is a host regression, not a real cloud/D2D transport or device test.

Physical restore, OEM behavior, Doze and audio delivery remain unverified. A failed
storage write is reported as restore failure; durable recovery after an OS/filesystem
failure still requires device evidence. Imported personal occurrences remain quarantined:
their explicit plan/revision/generation/epoch/provenance admission and persistent
Android execution are the next Alpha13 stages.

This increment revokes the weekly-enable preference; it does not cancel every existing
AlarmManager registration. The legacy personal one-shot snooze path does not use the
recurring-toggle check. Previously registered one-shot deliveries during a same-device
restore therefore still need the planned persistent personal execution and shared
receiver/service authority fence. No broader all-output restore guarantee is claimed.
