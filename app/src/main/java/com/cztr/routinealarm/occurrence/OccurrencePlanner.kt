package com.cztr.routinealarm.occurrence

import com.cztr.routinealarm.RoutineEvent
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Deterministic calendar expansion; no AlarmManager side effects. */
class OccurrencePlanner(private val clock: Clock) {
    fun plan(
        events: List<RoutineEvent>, startDate: LocalDate, days: Int, zone: ZoneId,
        scheduleRevision: String, laterOverlap: Boolean = false, isTest: Boolean = false
    ): List<RoutineOccurrence> {
        require(days in 1..366) { "Plan window must be between 1 and 366 days" }
        require(scheduleRevision.isNotBlank()) { "A schedule revision is required" }
        require(events.map { it.id }.distinct().size == events.size) { "Duplicate rule IDs" }
        val recordedAt = Instant.ofEpochMilli(clock.millis())
        return (0 until days).flatMap { day ->
            val date = startDate.plusDays(day.toLong())
            events.filter { it.enabled && date.dayOfWeek in it.daysOfWeek }.map { event ->
                val time = LocalTime.of(event.hour, event.minute)
                // Match existing AlarmScheduler: gaps move forward by the gap length;
                // overlaps use the earlier offset unless an explicit later fold is requested.
                val zoned = date.atTime(time).atZone(zone).let {
                    if (laterOverlap) it.withLaterOffsetAtOverlap() else it.withEarlierOffsetAtOverlap()
                }
                val instant = zoned.toInstant()
                val id = identity(listOf(if (isTest) "test" else "routine", event.id,
                    scheduleRevision, date.toString(), time.toString(), zone.id,
                    zoned.offset.totalSeconds.toString(), instant.toEpochMilli().toString()))
                RoutineOccurrence(
                    id = id, eventId = event.id, scheduleRevision = scheduleRevision,
                    nominalDate = date, nominalTime = time, zoneId = zone.id,
                    offsetSeconds = zoned.offset.totalSeconds, plannedAt = instant,
                    currentTriggerAt = instant, lastUpdatedAt = recordedAt, traceId = "occurrence:$id",
                    title = event.title, spokenText = event.spokenText, category = event.category,
                    mode = event.mode, isTest = isTest
                )
            }
        }.sortedWith(compareBy({ it.plannedAt }, { it.id }))
    }

    private fun identity(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
