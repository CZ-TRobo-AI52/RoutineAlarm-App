package com.cztr.routinealarm.ui.studio

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Pure read model. It cannot enable, disable, edit or persist a routine. */
object StudioUiMapper {
    private val german = Locale.GERMAN

    fun map(input: StudioUiInput): StudioUiState {
        val visible = input.routines.asSequence()
            .filter { routine ->
                when (input.filter) {
                    StudioFilter.ALL -> true
                    StudioFilter.ACTIVE -> routine.enabled
                    StudioFilter.PAUSED -> !routine.enabled
                }
            }
            .sortedWith(compareBy({ it.minuteOfDay }, { it.title }, { it.id }))
            .toList()
        val items = visible.map { routine ->
            StudioRoutineItem(
                id = routine.id,
                title = routine.title,
                timeLabel = time(routine.minuteOfDay),
                daysLabel = days(routine.days),
                statusLabel = if (routine.enabled) "Im Plan" else "Deaktiviert",
                categoryLabel = routine.categoryLabel,
            )
        }
        val selected = input.routines.firstOrNull { it.id == input.selectedId }?.let { routine ->
            StudioRoutineDetail(
                id = routine.id,
                title = routine.title,
                timeLabel = time(routine.minuteOfDay),
                daysLabel = days(routine.days),
                statusLabel = if (routine.enabled) "Im Plan" else "Deaktiviert",
                categoryLabel = routine.categoryLabel,
                modeLabel = routine.modeLabel,
                spokenText = routine.spokenText,
                authorityLabel = "Nur ansehen",
                detail = "Bearbeiten und Speichern werden erst aktiviert, wenn ein autorisierter, persistenter Routinevertrag verfügbar ist.",
            )
        }
        return StudioUiState(input.filter, StudioFilter.entries, items, selected)
    }

    private fun time(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
    private fun days(days: Set<DayOfWeek>) = DayOfWeek.entries.filter(days::contains)
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, german).take(2) }
}
