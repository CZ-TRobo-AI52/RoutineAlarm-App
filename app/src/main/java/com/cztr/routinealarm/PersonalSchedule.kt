package com.cztr.routinealarm

import java.time.DayOfWeek
import java.time.DayOfWeek.*

object PersonalSchedule {

    private val MON_TO_SAT: Set<DayOfWeek> =
        setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY)

    private val MON_TO_FRI: Set<DayOfWeek> =
        setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

    private val SATURDAY_ONLY: Set<DayOfWeek> =
        setOf(SATURDAY)

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
,

        event(
            id = "morning_collagen_coffee_drink_0545",
            days = MON_TO_SAT,
            hour = 5,
            minute = 45,
            title = "Kollagen-Kaffee trinken",
            spokenText = "Jetzt den Kollagen-Kaffee trinken. Fünf Minuten.",
            category = EventCategory.MORNING
        ),
        event("morning_teeth_0550", MON_TO_SAT, 5, 50, "Zähne putzen", "Jetzt Zähne putzen.", EventCategory.MORNING),
        event("morning_shower_transition_0553", MON_TO_SAT, 5, 53, "Zur Dusche und ausziehen", "Jetzt zur Dusche gehen und ausziehen.", EventCategory.MORNING),
        event("morning_shower_0555", MON_TO_SAT, 5, 55, "Duschen", "Jetzt duschen. Fünf Minuten.", EventCategory.MORNING),
        event("morning_dry_dress_0600", MON_TO_SAT, 6, 0, "Abtrocknen und anziehen", "Dusche beendet. Jetzt abtrocknen und anziehen.", EventCategory.MORNING),
        event("morning_departure_check_0603", MON_TO_SAT, 6, 3, "Tasche, Schlüssel, Handy, Fahrrad", "Jetzt Tasche, Schlüssel, Handy und Fahrrad kontrollieren.", EventCategory.MORNING)

    )

    /*
     * Montag bis Freitag variiert der Arbeitsbeginn zwischen 06:30 und 07:00.
     * Beide Optionen bleiben absichtlich deaktiviert, bis der Shift-Selector
     * implementiert ist. So feuern niemals versehentlich beide Abfahrtsalarme.
     */
    val workDepartureOptions: List<RoutineEvent> = listOf(
        event(
            id = "work_depart_0605_for_0630_start",
            days = MON_TO_FRI,
            hour = 6,
            minute = 5,
            title = "Abfahrt zur Arbeit für 06:30",
            spokenText = "Arbeitsbeginn ist um sechs Uhr dreißig. Jetzt mit dem Fahrrad losfahren.",
            category = EventCategory.WORK,
            mode = EventMode.ROUTINE,
            enabled = false
        ),
        event(
            id = "work_depart_0635_for_0700_start",
            days = MON_TO_FRI,
            hour = 6,
            minute = 35,
            title = "Abfahrt zur Arbeit für 07:00",
            spokenText = "Arbeitsbeginn ist um sieben Uhr. Jetzt mit dem Fahrrad losfahren.",
            category = EventCategory.WORK,
            mode = EventMode.ROUTINE,
            enabled = false
        ),
        event(
            id = "work_sat_depart_0635",
            days = SATURDAY_ONLY,
            hour = 6,
            minute = 35,
            title = "Abfahrt zur Samstagsarbeit",
            spokenText = "Samstagsarbeit beginnt um sieben Uhr. Jetzt mit dem Fahrrad losfahren.",
            category = EventCategory.WORK,
            mode = EventMode.ROUTINE
        )
    )

    val mealEvents: List<RoutineEvent> = listOf(
        event("meal_1_0900", EVERY_DAY, 9, 0, "Mahlzeit 1", "Es ist neun Uhr. Jetzt Mahlzeit eins.", EventCategory.MEAL, EventMode.ROUTINE),
        event("meal_2_1200", EVERY_DAY, 12, 0, "Mahlzeit 2", "Es ist zwölf Uhr. Jetzt Mahlzeit zwei.", EventCategory.MEAL, EventMode.ROUTINE)
    )

    val weekdayAfternoonEvents: List<RoutineEvent> = listOf(
        event("workday_bike_home_1530", MON_TO_FRI, 15, 30, "Feierabend und Fahrrad nach Hause", "Feierabend. Jetzt mit dem Fahrrad nach Hause.", EventCategory.WORK, EventMode.ROUTINE),
        event("workday_shower_1555", MON_TO_FRI, 15, 55, "Duschen", "Jetzt duschen. Zehn Minuten.", EventCategory.PERSONAL),
        event("workday_dry_dress_1605", MON_TO_FRI, 16, 5, "Abtrocknen und anziehen", "Dusche beendet. Jetzt abtrocknen und anziehen.", EventCategory.PERSONAL),
        event("workday_sprouts_1610", MON_TO_FRI, 16, 10, "Mungobohnen spülen", "Jetzt den Mungobohnen-Keimbehälter spülen.", EventCategory.PERSONAL, EventMode.ROUTINE),
        event("workday_water_reset_1612", MON_TO_FRI, 16, 12, "Wasser und Reset", "Jetzt Wasser trinken und kurz resetten.", EventCategory.PERSONAL),
        event("workday_reading_1620", MON_TO_FRI, 16, 20, "Lesen", "Jetzt zwanzig Minuten lesen.", EventCategory.PERSONAL),
        event("workday_english_1640", MON_TO_FRI, 16, 40, "Englisch", "Jetzt fünfzehn Minuten Englisch.", EventCategory.PERSONAL),
        event("workday_organisation_1655", MON_TO_FRI, 16, 55, "Organisation, Nachrichten und Termine", "Jetzt Organisation, Nachrichten und Termine erledigen.", EventCategory.PERSONAL),
        event("workday_water_toilet_1710", MON_TO_FRI, 17, 10, "Wasser und Toilette", "Jetzt Wasser trinken und zur Toilette.", EventCategory.PERSONAL),
        event("workday_training_prepare_1715", MON_TO_FRI, 17, 15, "Training vorbereiten", "Jetzt das Training vorbereiten.", EventCategory.TRAINING),
        event("workday_training_focus_1725", MON_TO_FRI, 17, 25, "Fokus auf Training", "Fünf Minuten bis zum Training. Fokus. Training beginnt um siebzehn Uhr dreißig.", EventCategory.TRAINING, EventMode.ROUTINE)
    )

    val eveningEvents: List<RoutineEvent> = listOf(
        event("evening_papa_2020", EVERY_DAY, 20, 20, "Zeit mit Papa", "Jetzt zehn Minuten Zeit mit Papa.", EventCategory.PERSONAL),
        event("evening_project_2030", EVERY_DAY, 20, 30, "ChatGPT, Projekt, Forschung und Lernen", "Jetzt ChatGPT, Projekt, Forschung und Lernen. Systematisch analysieren, verbessern und Ziele umsetzen.", EventCategory.PROJECT, EventMode.ROUTINE),
        event("evening_finances_2100", EVERY_DAY, 21, 0, "Finanzen", "Jetzt Finanzen.", EventCategory.EVENING),
        event("evening_messages_2110", EVERY_DAY, 21, 10, "Nachrichten", "Jetzt Nachrichten.", EventCategory.EVENING),
        event("evening_prepare_tomorrow_2120", EVERY_DAY, 21, 20, "Morgen vorbereiten", "Jetzt morgen vorbereiten.", EventCategory.EVENING),
        event("evening_filter_water_2125", EVERY_DAY, 21, 25, "Wasser filtern", "Jetzt Leitungswasser filtern und in zwei Kupferbehälter füllen: Trinkwasser und Wasser für Nüsse und Samen.", EventCategory.EVENING),
        event("evening_freezer_meal_2130", EVERY_DAY, 21, 30, "Mahlzeit aus Tiefkühlfach in Kühlschrank", "Jetzt die Tiefkühl-Mahlzeit für morgen in den Kühlschrank legen.", EventCategory.EVENING),
        event("evening_soak_nuts_2135", EVERY_DAY, 21, 35, "Nüsse und Samen einweichen", "Jetzt Nüsse und Samen über Nacht im Kupferbehälterwasser einweichen.", EventCategory.EVENING),
        event("evening_free_time_2140", EVERY_DAY, 21, 40, "Freie Zeit", "Jetzt freie Zeit.", EventCategory.EVENING),
        event("evening_hygiene_2220", EVERY_DAY, 22, 20, "Hygiene und Zähne", "Jetzt Hygiene und Zähne putzen.", EventCategory.EVENING, EventMode.ROUTINE),
        event("evening_jim_rohn_journals_2230", EVERY_DAY, 22, 30, "Jim-Rohn-Journals", "Jetzt die Jim-Rohn-Journals. Je zwei Minuten.", EventCategory.EVENING),
        event("evening_close_2235", EVERY_DAY, 22, 35, "Tagesabschluss und drei Prioritäten", "Jetzt Tagesabschluss und die drei Prioritäten für morgen festlegen.", EventCategory.EVENING),
        event("evening_quiet_free_2240", EVERY_DAY, 22, 40, "Ruhige freie Zeit", "Jetzt ruhige freie Zeit.", EventCategory.EVENING),
        event("evening_oil_lamp_on_2250", EVERY_DAY, 22, 50, "Öllampe an", "Jetzt die Öllampe anmachen und beaufsichtigt lassen.", EventCategory.EVENING),
        event("evening_sprouts_2255", EVERY_DAY, 22, 55, "Mungobohnen spülen", "Jetzt den Mungobohnen-Keimbehälter spülen.", EventCategory.EVENING, EventMode.ROUTINE),
        event("evening_triphala_chlorella_2258", EVERY_DAY, 22, 58, "Triphala und Chlorella", "Jetzt Triphala und Chlorella.", EventCategory.EVENING),
        event("evening_magnesium_2300", EVERY_DAY, 23, 0, "Magnesium", "Jetzt Magnesium-L-Threonat und Magnesiumbisglycinat.", EventCategory.EVENING),
        event("evening_oil_lamp_off_phone_2305", EVERY_DAY, 23, 5, "Öllampe aus und Handy weg", "Jetzt die Öllampe ausmachen und das Handy weglegen.", EventCategory.SLEEP, EventMode.ROUTINE),
        event("sleep_2320", EVERY_DAY, 23, 20, "Schlafen", "Es ist dreiundzwanzig Uhr zwanzig. Jetzt schlafen.", EventCategory.SLEEP, EventMode.ROUTINE)
    )

    val allEvents: List<RoutineEvent> =
        morningEvents +
            workDepartureOptions +
            mealEvents +
            weekdayAfternoonEvents +
            eveningEvents
}
