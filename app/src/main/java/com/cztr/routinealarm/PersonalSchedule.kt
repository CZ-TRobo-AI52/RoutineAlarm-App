package com.cztr.routinealarm

import java.time.DayOfWeek
import java.time.DayOfWeek.*

object PersonalSchedule {

    private val MON_TO_SAT: Set<DayOfWeek> =
        setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY)

    private val MON_TO_FRI: Set<DayOfWeek> =
        setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

    private val EVERY_DAY: Set<DayOfWeek> =
        DayOfWeek.entries.toSet()

    private fun event(
        id: String,
        days: Set<DayOfWeek>,
        hour: Int,
        minute: Int,
        title: String,
        spokenText: String = title,
        category: EventCategory,
        mode: EventMode = EventMode.ANNOUNCEMENT,
        enabled: Boolean = true
    ) = RoutineEvent(
        id = id,
        daysOfWeek = days,
        hour = hour,
        minute = minute,
        title = title,
        spokenText = spokenText,
        category = category,
        mode = mode,
        enabled = enabled
    )

    val morningEvents: List<RoutineEvent> = listOf(

        event(
            id = "morning_wake_0505",
            days = EVERY_DAY,
            hour = 5,
            minute = 5,
            title = "Aufstehen und Mungobohnen spülen",
            spokenText = "Guten Morgen. Aufstehen. Jetzt den Mungobohnen-Keimbehälter spülen.",
            category = EventCategory.MORNING,
            mode = EventMode.ROUTINE
        ),

        event(
            id = "morning_prejog_drink_prepare_0507",
            days = MON_TO_SAT,
            hour = 5,
            minute = 7,
            title = "Getränk vor dem Joggen herstellen",
            spokenText = "Jetzt das Getränk vor dem Joggen herstellen: Apfelessig, Zitronensaft, Honig, Ingwersaft und eine Prise Meersalz.",
            category = EventCategory.MORNING
        ),

        event(
            id = "morning_prejog_drink_0511",
            days = MON_TO_SAT,
            hour = 5,
            minute = 11,
            title = "Getränk und Morgen-Supplements",
            spokenText = "Jetzt das Getränk trinken, Neem-Kurkuma-Chlorella-Honig-Kugeln nehmen und Magnesiumcitrat einnehmen.",
            category = EventCategory.MORNING
        ),

        event(
            id = "morning_nasal_rinse_0514",
            days = MON_TO_SAT,
            hour = 5,
            minute = 14,
            title = "Nasenspülung",
            spokenText = "Jetzt die Nasenspülung mit Salz durchführen.",
            category = EventCategory.MORNING
        ),

        event(
            id = "morning_fire_breath_0519",
            days = MON_TO_SAT,
            hour = 5,
            minute = 19,
            title = "Feueratem",
            spokenText = "Jetzt Feueratem Yoga.",
            category = EventCategory.MORNING
        ),

        event(
            id = "morning_shoes_0521",
            days = MON_TO_SAT,
            hour = 5,
            minute = 21,
            title = "Schuhe anziehen und raus",
            spokenText = "Schuhe anziehen. Jetzt raus zum Joggen.",
            category = EventCategory.MORNING
        ),

        event(
            id = "morning_jog_0523",
            days = MON_TO_SAT,
            hour = 5,
            minute = 23,
            title = "Joggen",
            spokenText = "Joggen beginnt jetzt. Zehn Minuten.",
            category = EventCategory.MORNING,
            mode = EventMode.ROUTINE
        ),

        event(
            id = "morning_jog_end_0533",
            days = MON_TO_SAT,
            hour = 5,
            minute = 33,
            title = "Joggen beendet",
            spokenText = "Joggen beendet. Jetzt zurück und direkt mit der Morgenroutine weitermachen.",
            category = EventCategory.MORNING
        ),

        event(
            id = "morning_breathing_0534",
            days = MON_TO_SAT,
            hour = 5,
            minute = 34,
            title = "Wechselatmung",
            spokenText = "Jetzt drei Minuten Wechselatmung.",
            category = EventCategory.MORNING
        ),

        event(
            id = "morning_trataka_0537",
            days = MON_TO_SAT,
            hour = 5,
            minute = 37,
            title = "Trataka",
            spokenText = "Jetzt drei Minuten Trataka und Augenübung mit der Kerze.",
            category = EventCategory.MORNING
        ),

        event(
            id = "morning_collagen_coffee_prepare_0540",
            days = MON_TO_SAT,
            hour = 5,
            minute = 40,
            title = "Kollagen-Kaffee herstellen",
            spokenText = "Jetzt Kollagen-Kaffee herstellen: zehn Gramm Kollagen, fünf Gramm Inulin, ein Teelöffel Honig und ein halber Teelöffel Kokosöl.",
            category = EventCategory.MORNING
        )
    )
}
