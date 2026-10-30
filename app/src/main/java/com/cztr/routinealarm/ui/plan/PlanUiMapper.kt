package com.cztr.routinealarm.ui.plan

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Pure UI mapper. It does not read preferences, register alarms or infer delivery outcomes. */
object PlanUiMapper {
    private val german = Locale.GERMAN

    fun map(input: PlanUiInput): PlanUiState {
        val availability = when {
            !input.planEnabled -> PlanAvailability.PAUSED
            !input.exactAllowed -> PlanAvailability.PERMISSION_REQUIRED
            else -> PlanAvailability.ACTIVE
        }
        val title: String
        val detail: String
        val action: String
        when (availability) {
            PlanAvailability.PAUSED -> {
                title = "Wochenplan pausiert"
                detail = "${input.activeEventCount} Ereignisse stehen als Vorschau bereit. Erst die Aktivierung fordert eine Android-Planung an."
                action = "Wochenplan aktivieren"
            }
            PlanAvailability.PERMISSION_REQUIRED -> {
                title = "Exakte Wecker fehlen"
                detail = "Der Plan bleibt gespeichert. Android hat exakte Wecker nicht freigegeben; die Liste ist nur eine Vorschau."
                action = "System prüfen"
            }
            PlanAvailability.ACTIVE -> {
                title = "Wochenplan aktiv"
                detail = "${input.activeEventCount} Ereignisse gehören zum geplanten Katalog. Diese Ansicht bestätigt keine Zustellung oder hörbare Ausgabe."
                action = "Wochenplan pausieren"
            }
        }
        val dayChoices = DayOfWeek.entries.map { day ->
            PlanDayChoice(day, day.getDisplayName(TextStyle.SHORT, german).take(2)
                .replaceFirstChar { it.titlecase(german) }, day == input.selectedDay)
        }
        val events = input.events.asSequence()
            .filter { it.enabled && input.selectedDay in it.days }
            .sortedWith(compareBy({ it.minuteOfDay }, { it.id }))
            .map(::toUi)
            .toList()
        val training = input.training.asSequence()
            .filter { it.enabled }
            .sortedWith(compareBy({ it.minuteOfDay }, { it.id }))
            .map(::toUi)
            .toList()
        return PlanUiState(
            availability = availability,
            title = title,
            detail = detail,
            primaryActionLabel = action,
            selectedDay = input.selectedDay,
            dayTitle = input.selectedDay.getDisplayName(TextStyle.FULL, german)
                .replaceFirstChar { it.titlecase(german) },
            dayChoices = dayChoices,
            events = events,
            training = training,
            showTrainingDetails = input.showTrainingDetails,
        )
    }

    private fun toUi(item: PlanProjectedItem) = PlanUiItem(
        id = item.id,
        title = item.title,
        timeLabel = "%02d:%02d".format(item.minuteOfDay / 60, item.minuteOfDay % 60),
        categoryLabel = item.categoryLabel,
    )
}
