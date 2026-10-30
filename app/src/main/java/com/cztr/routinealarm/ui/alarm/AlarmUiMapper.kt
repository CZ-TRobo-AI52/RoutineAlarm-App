package com.cztr.routinealarm.ui.alarm

import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure alarm presentation. It never grants delivery authority or records an outcome. */
object AlarmUiMapper {
    private val german = Locale.GERMAN
    private val clock = DateTimeFormatter.ofPattern("HH:mm", german)
    private val date = DateTimeFormatter.ofPattern("EEEE, d. MMMM", german)

    fun map(input: AlarmUiInput): AlarmUiState {
        val pending = input.pendingSnoozeMinutes?.takeIf { it in 1..5 }
        val snooze = when {
            !input.deliveryAllowed -> emptyList()
            pending != null -> listOf(pending)
            else -> (1..5).toList()
        }
        return AlarmUiState(
            clockText = input.now.format(clock),
            dateText = input.now.format(date).replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(german) else it.toString()
            },
            title = input.title,
            spokenText = input.spokenText.takeIf { it.isNotBlank() && it != input.title },
            snoozeMinutes = snooze,
            pendingSnoozeMinutes = pending,
            showCloseOutput = input.outputInstanceAvailable,
            warning = if (input.deliveryAllowed) null else
                "Diese Alarmidentität ist nicht mehr autorisiert. Snooze wird nicht erneut vorbereitet.",
            snoozeBoundary = if (pending != null)
                "Eine vorherige Verschiebung ist noch nicht eindeutig bestätigt. Nur die gleiche Dauer darf erneut versucht werden."
            else
                "Snooze plant einen neuen Android-Effekt. Der Rückgabewert bestätigt nicht, dass der spätere Alarm hörbar sein wird.",
            evidenceBoundary =
                "Erledigt und Überspringen beenden in diesem Legacy-Pfad die aktuelle Ausgabe; eine persistente persönliche Outcome-Erfassung ist noch nicht integriert.",
        )
    }
}
