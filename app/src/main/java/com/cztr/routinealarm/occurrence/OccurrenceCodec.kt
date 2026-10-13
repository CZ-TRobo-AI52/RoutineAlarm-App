package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Versioned snapshots retain exact Instants; indexed database times use UTC epoch milliseconds. */
internal object OccurrenceCodec {
    fun encode(value: RoutineOccurrence): String = JSONObject().apply {
        put("version", 1)
        put("id", value.id)
        put("eventId", value.eventId)
        put("scheduleRevision", value.scheduleRevision)
        put("nominalDate", value.nominalDate.toString())
        put("nominalTime", value.nominalTime.toString())
        put("zoneId", value.zoneId)
        put("offsetSeconds", value.offsetSeconds)
        put("plannedAt", value.plannedAt.toString())
        put("currentTriggerAt", value.currentTriggerAt.toString())
        put("status", value.status.name)
        put("generation", value.generation)
        put("revision", value.revision)
        put("snoozeCount", value.snoozeCount)
        put("totalSnoozeMinutes", value.totalSnoozeMinutes)
        put("lastUpdatedAt", value.lastUpdatedAt.toString())
        put("traceId", value.traceId)
        put("title", value.title)
        put("spokenText", value.spokenText)
        put("category", value.category.name)
        put("mode", value.mode.name)
        put("isTest", value.isTest)
    }.toString()

    fun decode(json: String): RoutineOccurrence = JSONObject(json).let {
        check(it.getInt("version") == 1) { "Unsupported occurrence snapshot version" }
        RoutineOccurrence(
            id = it.getString("id"), eventId = it.getString("eventId"),
            scheduleRevision = it.getString("scheduleRevision"),
            nominalDate = LocalDate.parse(it.getString("nominalDate")),
            nominalTime = LocalTime.parse(it.getString("nominalTime")),
            zoneId = it.getString("zoneId"), offsetSeconds = it.getInt("offsetSeconds"),
            plannedAt = Instant.parse(it.getString("plannedAt")),
            currentTriggerAt = Instant.parse(it.getString("currentTriggerAt")),
            status = OccurrenceStatus.valueOf(it.getString("status")),
            generation = it.getLong("generation"), revision = it.getLong("revision"),
            snoozeCount = it.getInt("snoozeCount"), totalSnoozeMinutes = it.getInt("totalSnoozeMinutes"),
            lastUpdatedAt = Instant.parse(it.getString("lastUpdatedAt")),
            traceId = it.getString("traceId"), title = it.getString("title"),
            spokenText = it.getString("spokenText"), category = EventCategory.valueOf(it.getString("category")),
            mode = EventMode.valueOf(it.getString("mode")), isTest = it.getBoolean("isTest"),
        )
    }

    fun encode(action: ExecutionAction): String = JSONObject().apply {
        put("version", 1)
        put("id", action.id)
        put("occurrenceId", action.occurrenceId)
        put("type", action.type.name)
        put("actor", action.actor.name)
        put("occurredAt", action.occurredAt.toString())
        put("expectedRevision", action.expectedRevision)
        put("expectedGeneration", action.expectedGeneration)
        put("snoozeMinutes", action.snoozeMinutes ?: JSONObject.NULL)
        put("source", action.source)
        put("reason", action.reason ?: JSONObject.NULL)
    }.toString()

    fun decodeAction(json: String): ExecutionAction = JSONObject(json).let {
        check(it.getInt("version") == 1) { "Unsupported action snapshot version" }
        ExecutionAction(
            id = it.getString("id"), occurrenceId = it.getString("occurrenceId"),
            type = ActionType.valueOf(it.getString("type")), actor = Actor.valueOf(it.getString("actor")),
            occurredAt = Instant.parse(it.getString("occurredAt")),
            expectedRevision = it.getLong("expectedRevision"), expectedGeneration = it.getLong("expectedGeneration"),
            snoozeMinutes = if (it.isNull("snoozeMinutes")) null else it.getInt("snoozeMinutes"),
            source = it.getString("source"), reason = if (it.isNull("reason")) null else it.getString("reason"),
        )
    }
}
