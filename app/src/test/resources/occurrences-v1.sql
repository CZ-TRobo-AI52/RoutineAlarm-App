-- Frozen schema from 7eb1ae07df33b04b580c9526c8d51c6732bbf012
-- statement
CREATE TABLE schedule_revisions (
                revision_id TEXT PRIMARY KEY NOT NULL, zone_id TEXT NOT NULL, created_at INTEGER NOT NULL,
                UNIQUE(revision_id, zone_id)
            )
-- statement
CREATE TABLE occurrences (
                occurrence_id TEXT PRIMARY KEY NOT NULL, event_id TEXT NOT NULL, schedule_revision TEXT NOT NULL,
                nominal_date TEXT NOT NULL, nominal_time TEXT NOT NULL, zone_id TEXT NOT NULL,
                offset_seconds INTEGER NOT NULL, planned_at INTEGER NOT NULL,
                current_trigger_at INTEGER NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('SCHEDULED','TRIGGERED','SNOOZED','DONE','SKIPPED','MISSED')),
                generation INTEGER NOT NULL CHECK(generation >= 0), revision INTEGER NOT NULL CHECK(revision >= 0),
                snooze_count INTEGER NOT NULL CHECK(snooze_count >= 0),
                total_snooze_minutes INTEGER NOT NULL CHECK(total_snooze_minutes >= 0),
                last_updated_at INTEGER NOT NULL, is_test INTEGER NOT NULL CHECK(is_test IN (0,1)), snapshot TEXT NOT NULL,
                UNIQUE(event_id, schedule_revision, nominal_date, zone_id, planned_at, is_test),
                FOREIGN KEY(schedule_revision, zone_id) REFERENCES schedule_revisions(revision_id, zone_id)
            )
-- statement
CREATE TABLE execution_journal (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, action_id TEXT NOT NULL UNIQUE,
                occurrence_id TEXT NOT NULL REFERENCES occurrences(occurrence_id),
                action_type TEXT NOT NULL CHECK(action_type IN ('SCHEDULE','TRIGGER','DONE','SKIP','SNOOZE','MISS')),
                actor TEXT NOT NULL CHECK(actor IN ('USER','SYSTEM')),
                occurred_at INTEGER NOT NULL, recorded_at INTEGER NOT NULL,
                occurred_at_exact TEXT NOT NULL, recorded_at_exact TEXT NOT NULL,
                expected_revision INTEGER NOT NULL, expected_generation INTEGER NOT NULL,
                resulting_revision INTEGER NOT NULL, previous_status TEXT, resulting_status TEXT NOT NULL,
                generation INTEGER NOT NULL, snooze_minutes INTEGER, source TEXT NOT NULL, reason TEXT,
                outcome TEXT NOT NULL CHECK(outcome IN ('APPLIED','REJECTED','CONFLICT')),
                action_snapshot TEXT, result_snapshot TEXT NOT NULL, result_reason TEXT
            )
-- statement
CREATE INDEX occurrences_day ON occurrences(nominal_date, zone_id, is_test, planned_at)
-- statement
CREATE INDEX occurrences_due ON occurrences(status, current_trigger_at)
-- statement
CREATE INDEX journal_occurrence ON execution_journal(occurrence_id, sequence)
-- statement
CREATE TRIGGER journal_no_update BEFORE UPDATE ON execution_journal
                BEGIN SELECT RAISE(ABORT, 'Execution journal is append-only'); END
-- statement
CREATE TRIGGER journal_no_delete BEFORE DELETE ON execution_journal
                BEGIN SELECT RAISE(ABORT, 'Execution journal is append-only'); END
