package com.cztr.routinealarm.occurrence

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Portable data only: fixed tables/columns, bounded UTF-8 and no executable SQL or file paths.
 * The checksum detects damage; it does not authenticate the author or prove delivery.
 * Store epochs and activation permissions deliberately never leave this installation.
 */
internal object OccurrenceBackup {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ROWS = 50_000
    private const val MAX_STRING_BYTES = 256 * 1024
    private fun magic(version: Int) = "R52BKUP$version".toByteArray(Charsets.US_ASCII)

    data class Column(val name: String, val integer: Boolean = false, val nullable: Boolean = false)
    data class Table(val name: String, val columns: List<Column>, val order: String)
    data class Snapshot(val tables: List<List<List<Any?>>>, val formatVersion: Int = 2)
    private fun columns(spec: String) = spec.split(' ').map {
        Column(it.removeSuffix("?").removeSuffix(":"), it.removeSuffix("?").endsWith(":"), it.endsWith("?"))
    }
    val tables = listOf(
        Table("schedule_revisions", columns("revision_id zone_id created_at:"), "revision_id"),
        Table("occurrences", columns("occurrence_id event_id schedule_revision nominal_date nominal_time zone_id offset_seconds: planned_at: current_trigger_at: status generation: revision: snooze_count: total_snooze_minutes: last_updated_at: is_test: snapshot"), "occurrence_id"),
        Table("execution_journal", columns("sequence: action_id occurrence_id action_type actor occurred_at: recorded_at: occurred_at_exact recorded_at_exact expected_revision: expected_generation: resulting_revision: previous_status? resulting_status generation: snooze_minutes:? source reason? outcome action_snapshot? result_snapshot result_reason?"), "sequence"),
        Table("scheduling_attempts", columns("sequence: attempt_id occurrence_id revision: generation: trigger_at: started_at_exact"), "sequence"),
        Table("scheduling_receipts", columns("attempt_id outcome reason recorded_at_exact"), "attempt_id"),
        Table("personal_plan_commits", columns("event_id version: plan_revision fingerprint cutoff_exact recorded_at_exact"), "event_id, version"),
        Table("personal_plan_retirements", columns("occurrence_id event_id plan_version:"), "occurrence_id"),
    )

    fun read(db: SQLiteDatabase): Snapshot {
        var rowCount = 0
        var bytes = 64L
        return Snapshot(tables.map { table ->
            db.query(table.name, table.columns.map { it.name }.toTypedArray(), null, null, null, null, table.order).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        require(++rowCount <= MAX_ROWS) { "Backup contains too many records" }
                        add(table.columns.mapIndexed { index, column ->
                            val value = when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> { require(column.nullable); null }
                                Cursor.FIELD_TYPE_INTEGER -> { require(column.integer); cursor.getLong(index) }
                                Cursor.FIELD_TYPE_STRING -> { require(!column.integer); cursor.getString(index) }
                                else -> error("Unsupported stored value")
                            }
                            bytes += if (value is String) utf8(value).size + 5L else 9L
                            require(bytes <= MAX_BYTES) { "Backup exceeds the 8 MiB limit" }
                            value
                        })
                    }
                }
            }
        })
    }

    fun encode(snapshot: Snapshot): ByteArray {
        require(snapshot.formatVersion in 1..2)
        require(snapshot.tables.size == tables.size)
        if (snapshot.formatVersion == 1) require(snapshot.tables.drop(5).all { it.isEmpty() }) {
            "Legacy backup cannot represent personal authority"
        }
        val encodedTables = if (snapshot.formatVersion == 1) tables.take(5) else tables
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            encodedTables.forEachIndexed { tableIndex, table ->
                val rows = snapshot.tables[tableIndex]
                output.writeInt(rows.size)
                rows.forEach { row -> table.columns.forEachIndexed { index, column ->
                    val value = row[index]
                    if (value == null) output.writeByte(0) else {
                        output.writeByte(1)
                        if (column.integer) output.writeLong(value as Long) else {
                            val text = utf8(value as String)
                            output.writeInt(text.size); output.write(text)
                        }
                    }
                } }
                require(payload.size() <= MAX_BYTES - 48) { "Backup exceeds the 8 MiB limit" }
            }
        }
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use {
            it.write(magic(snapshot.formatVersion)); it.writeInt(snapshot.formatVersion); it.writeInt(payload.size()); payload.writeTo(it)
        }
        val data = result.toByteArray()
        return data + MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun decode(bytes: ByteArray): Snapshot {
        try {
            require(bytes.size in 48..MAX_BYTES) { "Invalid backup size" }
            val data = bytes.copyOfRange(0, bytes.size - 32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(data),
                bytes.copyOfRange(bytes.size - 32, bytes.size))) { "Backup checksum mismatch" }
            val input = DataInputStream(ByteArrayInputStream(data))
            val signature = ByteArray(8).also(input::readFully)
            val version = input.readInt()
            require(version in 1..2 && signature.contentEquals(magic(version))) { "Unsupported backup format" }
            require(input.readInt() == input.available()) { "Invalid backup length" }
            var totalRows = 0
            val decodedTables = (if (version == 1) tables.take(5) else tables).map { table ->
                val count = input.readInt()
                require(count in 0..MAX_ROWS && totalRows + count <= MAX_ROWS && count <= input.available()) { "Invalid record count" }
                totalRows += count
                List(count) { table.columns.map { column ->
                    when (input.readUnsignedByte()) {
                        0 -> { require(column.nullable) { "Required field is null" }; null }
                        1 -> if (column.integer) input.readLong() else {
                            val size = input.readInt()
                            require(size in 0..MAX_STRING_BYTES && size <= input.available()) { "Invalid text length" }
                            val text = ByteArray(size).also(input::readFully)
                            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text)).toString()
                        }
                        else -> throw IllegalArgumentException("Invalid field marker")
                    }
                } }
            }
            val snapshot = Snapshot(if (version == 1) decodedTables + listOf(emptyList(), emptyList()) else decodedTables, version)
            require(input.available() == 0) { "Trailing backup content" }
            validate(snapshot)
            return snapshot
        } catch (error: IllegalArgumentException) { throw error }
        catch (error: Exception) { throw IllegalArgumentException("Invalid occurrence backup", error) }
    }

    fun insert(db: SQLiteDatabase, snapshot: Snapshot) {
        tables.forEachIndexed { index, table -> snapshot.tables[index].forEach { row ->
            val values = ContentValues()
            table.columns.forEachIndexed { columnIndex, column -> when (val value = row[columnIndex]) {
                null -> values.putNull(column.name)
                is Long -> values.put(column.name, value)
                is String -> values.put(column.name, value)
                else -> error("Invalid backup field")
            } }
            db.insertOrThrow(table.name, null, values)
        } }
    }

    /** Validate independent of destination SQL constraints before any target data is written. */
    fun validate(snapshot: Snapshot) {
        require(snapshot.tables.size == tables.size)
        fun rows(index: Int) = snapshot.tables[index].map { row ->
            require(row.size == tables[index].columns.size)
            tables[index].columns.mapIndexed { i, column ->
                val value = row[i]
                require(if (value == null) column.nullable else if (column.integer) value is Long else value is String)
                column.name to value
            }.toMap()
        }
        val revisions = rows(0).associateUnique("revision_id")
        revisions.values.forEach { r ->
            require(r.text("revision_id").isNotBlank()); ZoneId.of(r.text("zone_id"))
        }
        val occurrences = rows(1).associateUnique("occurrence_id")
        val identities = mutableSetOf<List<Any?>>()
        val expected = occurrences.mapValues { (_, row) ->
            val value = occurrence(row.text("snapshot"))
            require(row.text("occurrence_id") == value.id && row.text("event_id") == value.eventId &&
                row.text("schedule_revision") == value.scheduleRevision && row.text("nominal_date") == value.nominalDate.toString() &&
                row.text("nominal_time") == value.nominalTime.toString() && row.text("zone_id") == value.zoneId &&
                row.number("offset_seconds") == value.offsetSeconds.toLong() && row.number("planned_at") == value.plannedAt.toEpochMilli() &&
                row.number("current_trigger_at") == value.currentTriggerAt.toEpochMilli() && row.text("status") == value.status.name &&
                row.number("generation") == value.generation && row.number("revision") == value.revision &&
                row.number("snooze_count") == value.snoozeCount.toLong() && row.number("total_snooze_minutes") == value.totalSnoozeMinutes.toLong() &&
                row.number("last_updated_at") == value.lastUpdatedAt.toEpochMilli() && row.number("is_test") == if (value.isTest) 1L else 0L)
            require(revisions[value.scheduleRevision]?.text("zone_id") == value.zoneId) { "Missing schedule revision" }
            require(identities.add(listOf(value.eventId, value.scheduleRevision, value.nominalDate, value.zoneId, value.plannedAt.toEpochMilli(), value.isTest)))
            value
        }
        val journal = rows(2)
        journal.associateUnique("action_id")
        val state = mutableMapOf<String, RoutineOccurrence>()
        val historical = mutableMapOf<Pair<String, Long>, RoutineOccurrence>()
        var lastSequence = 0L
        journal.forEach { row ->
            val sequence = row.number("sequence")
            require(sequence > lastSequence); lastSequence = sequence
            val id = row.text("occurrence_id")
            require(id in expected)
            val result = occurrence(row.text("result_snapshot"))
            require(result.id == id)
            val occurred = exactTime(row, "occurred_at_exact", "occurred_at")
            exactTime(row, "recorded_at_exact", "recorded_at")
            val current = state[id]
            val encodedAction = row["action_snapshot"] as String?
            if (current == null) {
                require(encodedAction == null && row.text("action_id") == "schedule:$id" && row.text("action_type") == "SCHEDULE" &&
                    row.text("actor") == "SYSTEM" && row.text("outcome") == "APPLIED" && row["previous_status"] == null &&
                    row.number("expected_revision") == 0L && row.number("expected_generation") == 0L && row["snooze_minutes"] == null &&
                    row["result_reason"] == null && row["reason"] == null && row.text("source").isNotBlank() && occurred == result.lastUpdatedAt)
                require(result.status == OccurrenceStatus.SCHEDULED && result.revision == 0L && result.generation == 0L &&
                    result.snoozeCount == 0 && result.totalSnoozeMinutes == 0 && result.currentTriggerAt == result.plannedAt)
            } else {
                require(encodedAction != null)
                val action = action(encodedAction)
                require(action.id.isNotBlank() && !action.id.startsWith("schedule:") && action.id == row.text("action_id") &&
                    action.occurrenceId == id && action.type.name == row.text("action_type") && action.actor.name == row.text("actor") &&
                    action.occurredAt == occurred && action.expectedRevision == row.number("expected_revision") &&
                    action.expectedGeneration == row.number("expected_generation") && action.snoozeMinutes?.toLong() == row["snooze_minutes"] &&
                    action.source == row.text("source") && current.status.name == row["previous_status"])
                val decision = OccurrenceTransitions.apply(current, action)
                require(decision.outcome.name == row.text("outcome") && decision.occurrence == result &&
                    decision.reason == row["result_reason"] && (decision.reason ?: action.reason) == row["reason"]) { "Journal transition mismatch" }
            }
            require(row.number("resulting_revision") == result.revision &&
                row.number("generation") == result.generation && row.text("resulting_status") == result.status.name)
            state[id] = result
            historical[id to result.revision] = result
        }
        require(state == expected) { "Journal does not reproduce the saved occurrences" }
        val attempts = rows(3).associateUnique("attempt_id")
        lastSequence = 0
        attempts.values.forEach { row ->
            require(row.number("sequence") > lastSequence); lastSequence = row.number("sequence")
            require(row.text("attempt_id").isNotBlank())
            val current = historical[row.text("occurrence_id") to row.number("revision")]
            // Attempts bind to their journal-proven historical state, not today's rule registry
            // or the current projection. An imported receipt never grants delivery permission.
            require(current != null && current.status in setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.SNOOZED) &&
                current.generation == row.number("generation") && current.currentTriggerAt.toEpochMilli() == row.number("trigger_at"))
            Instant.parse(row.text("started_at_exact")).toEpochMilli()
        }
        rows(4).associateUnique("attempt_id").values.forEach { row ->
            require(row.text("attempt_id") in attempts)
            val outcome = SchedulingOutcome.valueOf(row.text("outcome"))
            val reason = SchedulingReason.valueOf(row.text("reason"))
            require(when (outcome) {
                SchedulingOutcome.CALL_RETURNED -> reason == SchedulingReason.RETURNED
                SchedulingOutcome.CALL_FAILED -> reason in setOf(SchedulingReason.PLATFORM_EXCEPTION, SchedulingReason.PERMISSION_DENIED)
                SchedulingOutcome.NOT_CALLED -> reason in setOf(SchedulingReason.PERMISSION_DENIED, SchedulingReason.STALE, SchedulingReason.EXPIRED)
            })
            Instant.parse(row.text("recorded_at_exact")).toEpochMilli()
        }
        // These are saved historical bindings, not authenticated external authority. Older plan
        // commits do not record candidate membership, so never invent it from today's projection.
        val commits = linkedMapOf<Pair<String, Long>, Map<String, Any?>>()
        val versions = mutableMapOf<String, Long>()
        val revisionsPerEvent = mutableSetOf<Pair<String, String>>()
        rows(5).forEach { row ->
            val event = row.text("event_id")
            OccurrenceScope.PersonalEvent(event)
            val version = row.number("version")
            require(version == Math.addExact(versions[event] ?: 0L, 1L)) { "Incomplete plan history" }
            versions[event] = version
            require(row.text("plan_revision").isNotBlank())
            require(revisionsPerEvent.add(event to row.text("plan_revision"))) { "Reused plan revision" }
            require(row.text("fingerprint").matches(Regex("[0-9a-f]{64}"))) { "Invalid plan fingerprint" }
            Instant.parse(row.text("cutoff_exact")).toEpochMilli()
            Instant.parse(row.text("recorded_at_exact")).toEpochMilli()
            require(commits.put(event to version, row) == null)
        }
        rows(6).associateUnique("occurrence_id").values.forEach { row ->
            val saved = expected[row.text("occurrence_id")]
            val commit = commits[row.text("event_id") to row.number("plan_version")]
            require(saved != null && commit != null) { "Retirement without occurrence or plan" }
            require(!saved.isTest && saved.eventId == row.text("event_id")) { "Cross-scope retirement" }
            require(saved.status == OccurrenceStatus.SCHEDULED && saved.revision == 0L &&
                saved.generation == 0L && saved.snoozeCount == 0 && saved.totalSnoozeMinutes == 0 &&
                saved.currentTriggerAt == saved.plannedAt &&
                saved.plannedAt > Instant.parse(commit.text("cutoff_exact"))) { "Invalid retired execution" }
        }
    }

    private fun occurrence(text: String): RoutineOccurrence {
        val fields = flatJson(text)
        val value = OccurrenceCodec.decode(text)
        require(fields == flatJson(OccurrenceCodec.encode(value))) { "Unexpected occurrence snapshot fields or types" }
        require(listOf(value.id, value.eventId, value.scheduleRevision, value.traceId).all { it.isNotBlank() })
        require(value.generation >= 0 && value.revision >= 0 && value.snoozeCount >= 0 && value.totalSnoozeMinutes >= 0)
        val offset = ZoneOffset.ofTotalSeconds(value.offsetSeconds)
        val zone = ZoneId.of(value.zoneId)
        // The planner retains nominal local time across a DST gap and records the resolved instant.
        val resolved = value.nominalDate.atTime(value.nominalTime).atZone(zone)
        require(listOf(resolved.withEarlierOffsetAtOverlap(), resolved.withLaterOffsetAtOverlap()).any {
            it.offset == offset && it.toInstant() == value.plannedAt
        })
        value.plannedAt.toEpochMilli(); value.currentTriggerAt.toEpochMilli(); value.lastUpdatedAt.toEpochMilli()
        return value
    }
    private fun action(text: String): ExecutionAction {
        val fields = flatJson(text)
        val value = OccurrenceCodec.decodeAction(text)
        require(fields == flatJson(OccurrenceCodec.encode(value))) { "Unexpected action snapshot fields or types" }
        value.occurredAt.toEpochMilli()
        return value
    }
    private fun exactTime(row: Map<String, Any?>, exact: String, millis: String): Instant =
        Instant.parse(row.text(exact)).also { require(it.toEpochMilli() == row.number(millis)) }
    private fun Map<String, Any?>.text(key: String) = getValue(key) as String
    private fun Map<String, Any?>.number(key: String) = getValue(key) as Long
    private fun List<Map<String, Any?>>.associateUnique(key: String): Map<String, Map<String, Any?>> {
        val result = linkedMapOf<String, Map<String, Any?>>()
        forEach { require(result.put(it.text(key), it) == null) { "Duplicate backup identity" } }
        return result
    }
    private fun utf8(text: String): ByteArray {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        require(bytes.remaining() <= MAX_STRING_BYTES) { "Backup text is too long" }
        return ByteArray(bytes.remaining()).also(bytes::get)
    }

    /** Snapshots contain scalar fields only. Reject duplicate keys, coercions and JSONTokener extensions. */
    private fun flatJson(text: String): Map<String, Any?> {
        var position = 0
        fun whitespace() { while (position < text.length && text[position] in " \r\n\t") position++ }
        fun expect(char: Char) { whitespace(); require(position < text.length && text[position++] == char) }
        fun string(): String {
            whitespace(); val start = position; expect('"')
            var closed = false
            while (position < text.length) {
                val c = text[position++]
                if (c == '"') { closed = true; break }
                require(c.code >= 32)
                if (c == '\\') {
                    require(position < text.length)
                    when (text[position++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) { require(position < text.length && text[position++].digitToIntOrNull(16) != null) }
                        else -> throw IllegalArgumentException("Invalid JSON escape")
                    }
                }
            }
            require(closed)
            return JSONObject("{\"v\":" + text.substring(start, position) + "}").getString("v")
        }
        expect('{'); whitespace()
        val result = linkedMapOf<String, Any?>()
        if (position < text.length && text[position] != '}') while (true) {
            val key = string(); require(key !in result); expect(':'); whitespace()
            require(position < text.length)
            val value: Any? = if (text[position] == '"') string() else {
                val start = position
                while (position < text.length && text[position] !in ",} \r\n\t") position++
                val token = text.substring(start, position)
                when (token) {
                    "true" -> true; "false" -> false; "null" -> null
                    else -> { require(token.matches(Regex("-?(0|[1-9][0-9]*)"))); token.toLong() }
                }
            }
            result[key] = value
            whitespace(); require(position < text.length)
            if (text[position] == '}') break
            expect(',')
        }
        expect('}'); whitespace(); require(position == text.length)
        return result
    }
}
