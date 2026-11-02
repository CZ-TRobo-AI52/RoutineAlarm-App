package com.cztr.routinealarm.ui.studio

import android.os.Bundle
import com.cztr.routinealarm.*
import java.time.DayOfWeek
import java.util.UUID

data class StudioSaveRequest(val event: RoutineEvent, val expectedVersion: Long,
    val requestId: String, val expectedPlanVersion: Long, val expectedRestoreEpoch: String)

enum class StudioSaveStatus { IDLE, BUSY, ERROR, UNKNOWN_EFFECT, SAVED, CONFLICT }

/** UI draft only; the host owns persistence and all authority checks. */
data class StudioEditorDraft(
    val eventId: String, val expectedVersion: Long, val expectedPlanVersion: Long,
    val expectedRestoreEpoch: String, val title: String = "", val spokenText: String = "",
    val hour: String = "08", val minute: String = "00", val days: Set<DayOfWeek> = emptySet(),
    val category: EventCategory = EventCategory.PERSONAL, val mode: EventMode = EventMode.ROUTINE,
    val requestId: String = UUID.randomUUID().toString(), val submittedPayload: String? = null,
) {
    fun event(): RoutineEvent {
        require(title.isNotBlank()) { "Bitte einen Titel eingeben." }
        require(spokenText.isNotBlank()) { "Bitte einen Sprechtext eingeben." }
        require(days.isNotEmpty()) { "Bitte mindestens einen Wochentag auswählen." }
        val h = hour.toIntOrNull()
        val m = minute.toIntOrNull()
        require(h != null && h in 0..23 && m != null && m in 0..59) { "Bitte eine gültige Uhrzeit eingeben (00:00–23:59)." }
        return RoutineEvent(eventId, days, h, m, title.trim(), spokenText.trim(), category, mode, false)
    }

    fun toBundle() = Bundle().apply {
        putString("id", eventId); putLong("version", expectedVersion)
        putLong("planVersion", expectedPlanVersion); putString("epoch", expectedRestoreEpoch)
        putString("title", title); putString("speech", spokenText); putString("hour", hour); putString("minute", minute)
        putStringArrayList("days", ArrayList(days.map { it.name }))
        putString("category", category.name); putString("mode", mode.name)
        putString("request", requestId); putString("payload", submittedPayload)
    }

    companion object {
        fun create(expectedPlanVersion: Long, expectedRestoreEpoch: String) =
            StudioEditorDraft(UUID.randomUUID().toString(), 0, expectedPlanVersion, expectedRestoreEpoch)
        fun edit(event: RoutineEvent, expectedVersion: Long, expectedPlanVersion: Long, expectedRestoreEpoch: String) =
            StudioEditorDraft(event.id, expectedVersion, expectedPlanVersion, expectedRestoreEpoch,
                event.title, event.spokenText, event.hour.toString(), event.minute.toString(),
                event.daysOfWeek, event.category, event.mode)
        fun fromBundle(b: Bundle) = StudioEditorDraft(
            requireNotNull(b.getString("id")), b.getLong("version"), b.getLong("planVersion"),
            requireNotNull(b.getString("epoch")), b.getString("title", ""), b.getString("speech", ""),
            b.getString("hour", ""), b.getString("minute", ""),
            b.getStringArrayList("days").orEmpty().map { DayOfWeek.valueOf(it) }.toSet(),
            EventCategory.valueOf(requireNotNull(b.getString("category"))),
            EventMode.valueOf(requireNotNull(b.getString("mode"))),
            requireNotNull(b.getString("request")), b.getString("payload"))
    }
}
