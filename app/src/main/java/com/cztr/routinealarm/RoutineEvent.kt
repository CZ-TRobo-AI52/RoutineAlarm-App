package com.cztr.routinealarm

import java.time.DayOfWeek

enum class EventMode {
    ROUTINE,
    ANNOUNCEMENT
}

enum class EventCategory {
    MORNING,
    WORK,
    MEAL,
    TRAINING,
    PERSONAL,
    PROJECT,
    EVENING,
    SLEEP
}

data class RoutineEvent(
    val id: String,
    val daysOfWeek: Set<DayOfWeek>,
    val hour: Int,
    val minute: Int,
    val title: String,
    val spokenText: String = title,
    val category: EventCategory,
    val mode: EventMode,
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "RoutineEvent.id darf nicht leer sein" }
        require(daysOfWeek.isNotEmpty()) { "Mindestens ein Wochentag erforderlich" }
        require(hour in 0..23) { "hour muss zwischen 0 und 23 liegen" }
        require(minute in 0..59) { "minute muss zwischen 0 und 59 liegen" }
        require(title.isNotBlank()) { "title darf nicht leer sein" }
        require(spokenText.isNotBlank()) { "spokenText darf nicht leer sein" }
    }

    val minuteOfDay: Int
        get() = hour * 60 + minute
}
