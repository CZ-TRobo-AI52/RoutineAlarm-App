package com.cztr.routinealarm.ui.home

import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure presentation mapper. Domain scheduling is isolated in [HomeProjectionSource]. */
object HomeUiMapper {
    fun map(input: HomeUiInput): HomeUiState {
        val greeting = when (input.now.hour) {
            in 5..10 -> "Guten Morgen, Christian"
            in 11..17 -> "Guten Tag, Christian"
            else -> "Guten Abend, Christian"
        }
        val formatter = DateTimeFormatter.ofPattern("EEE, dd.MM. · HH:mm", Locale.GERMAN)
        val upcoming = input.projected.asSequence()
            .sortedWith(compareBy({ it.projectedAt.toInstant() }, { it.id }))
            .take(5)
            .map { projected ->
                HomeUpcomingItem(
                    id = projected.id,
                    title = projected.title,
                    timeLabel = formatter.format(projected.projectedAt),
                    categoryLabel = projected.categoryLabel,
                    projectedAt = projected.projectedAt,
                )
            }
            .toList()
        val history = listOf(HomeQuickAction.OPEN_HISTORY)

        if (!input.planEnabled) return HomeUiState(
            greeting = greeting,
            planState = HomePlanState.PAUSED,
            title = "Wochenplan pausiert",
            detail = "Im Plan kannst du die vorgesehenen Routinen ansehen und den Wochenplan aktivieren.",
            upcoming = emptyList(),
            primaryAction = HomeQuickAction.OPEN_PLAN,
            quickActions = history,
        )
        if (!input.exactAllowed) return HomeUiState(
            greeting = greeting,
            planState = HomePlanState.PERMISSION_REQUIRED,
            title = "Exakte Wecker fehlen",
            detail = "Android hat exakte Wecker nicht freigegeben. Die Termine unten sind nur eine Wochenplan-Vorschau.",
            upcoming = upcoming,
            primaryAction = HomeQuickAction.OPEN_SYSTEM,
            quickActions = listOf(HomeQuickAction.OPEN_PLAN) + history,
        )
        val next = upcoming.firstOrNull() ?: return HomeUiState(
            greeting = greeting,
            planState = HomePlanState.EMPTY,
            title = "Keine nächste Routine gefunden",
            detail = "Im Wochenplan ist aktuell keine aktivierte Routine enthalten.",
            upcoming = emptyList(),
            primaryAction = HomeQuickAction.OPEN_PLAN,
            quickActions = history,
        )
        val minutes = Duration.between(input.now.toInstant(), next.projectedAt.toInstant()).toMinutes()
        val countdown = when {
            minutes < 1 -> "in weniger als 1 Minute"
            minutes < 60 -> "in $minutes Minuten"
            else -> "in ${minutes / 60} Std. ${minutes % 60} Min."
        }
        return HomeUiState(
            greeting = greeting,
            planState = HomePlanState.PREVIEW,
            title = next.title,
            detail = "${next.timeLabel} Uhr · $countdown\n${next.categoryLabel}",
            upcoming = upcoming,
            primaryAction = HomeQuickAction.OPEN_PLAN,
            quickActions = history,
        )
    }
}
