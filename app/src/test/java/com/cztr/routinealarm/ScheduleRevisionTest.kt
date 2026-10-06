package com.cztr.routinealarm

import java.time.DayOfWeek
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRevisionTest {

    private val berlin =
        ZoneId.of("Europe/Berlin")

    private val utc =
        ZoneId.of("UTC")

    private fun event(
        id: String = "event-a",
        days: Set<DayOfWeek> =
            setOf(DayOfWeek.MONDAY),
        hour: Int = 8,
        minute: Int = 30,
        title: String = "Routine A",
        spokenText: String = title,
        category: EventCategory =
            EventCategory.PERSONAL,
        mode: EventMode =
            EventMode.ROUTINE,
        enabled: Boolean = true
    ) =
        RoutineEvent(
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

    @Test
    fun sameLogicalScheduleAlwaysProducesSameRevision() {
        val first =
            listOf(
                event(id = "b"),
                event(
                    id = "a",
                    days =
                        setOf(
                            DayOfWeek.WEDNESDAY,
                            DayOfWeek.MONDAY
                        ),
                    hour = 6,
                    minute = 15
                )
            )

        val reordered =
            first.reversed()

        val expected =
            ScheduleRevision.of(
                events = first,
                zone = berlin
            )

        val actual =
            ScheduleRevision.of(
                events = reordered,
                zone = berlin
            )

        assertEquals(
            expected,
            actual
        )

        assertTrue(
            expected.matches(
                Regex(
                    "^sched-v2:[0-9a-f]{64}$"
                )
            )
        )
    }

    @Test
    fun daySetOrderDoesNotChangeRevision() {
        val first =
            event(
                days =
                    linkedSetOf(
                        DayOfWeek.MONDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.FRIDAY
                    )
            )

        val reordered =
            first.copy(
                daysOfWeek =
                    linkedSetOf(
                        DayOfWeek.FRIDAY,
                        DayOfWeek.MONDAY,
                        DayOfWeek.WEDNESDAY
                    )
            )

        assertEquals(
            ScheduleRevision.of(
                events = listOf(first),
                zone = berlin
            ),
            ScheduleRevision.of(
                events = listOf(reordered),
                zone = berlin
            )
        )
    }

    @Test
    fun presentationChangesDoNotChangeRevision() {
        val original =
            event(
                title = "Aufstehen",
                spokenText = "Bitte aufstehen",
                category =
                    EventCategory.MORNING
            )

        val renamed =
            original.copy(
                title =
                    "Aufstehen und Wasser trinken",
                spokenText =
                    "Jetzt aufstehen und Wasser trinken",
                category =
                    EventCategory.PERSONAL
            )

        assertEquals(
            ScheduleRevision.of(
                events = listOf(original),
                zone = berlin
            ),
            ScheduleRevision.of(
                events = listOf(renamed),
                zone = berlin
            )
        )
    }

    @Test
    fun executionRelevantChangesProduceDifferentRevision() {
        val original =
            event()

        val variants =
            listOf(
                original.copy(
                    daysOfWeek =
                        setOf(
                            DayOfWeek.TUESDAY
                        )
                ),
                original.copy(
                    hour = 9
                ),
                original.copy(
                    minute = 31
                ),
                original.copy(
                    mode =
                        EventMode.ANNOUNCEMENT
                ),
                original.copy(
                    enabled = false
                )
            )

        val baseline =
            ScheduleRevision.of(
                events = listOf(original),
                zone = berlin
            )

        variants.forEach { changed ->
            assertNotEquals(
                baseline,
                ScheduleRevision.of(
                    events =
                        listOf(changed),
                    zone = berlin
                )
            )
        }
    }

    @Test
    fun changingStableEventIdChangesRevision() {
        val original =
            event(
                id = "routine-a"
            )

        val changed =
            original.copy(
                id = "routine-b"
            )

        assertNotEquals(
            ScheduleRevision.of(
                events = listOf(original),
                zone = berlin
            ),
            ScheduleRevision.of(
                events = listOf(changed),
                zone = berlin
            )
        )
    }

    @Test
    fun changingZoneChangesRevision() {
        val events =
            listOf(
                event()
            )

        assertNotEquals(
            ScheduleRevision.of(
                events = events,
                zone = berlin
            ),
            ScheduleRevision.of(
                events = events,
                zone = utc
            )
        )
    }

    @Test
    fun duplicateEventIdsAreRejected() {
        val first =
            event(
                id = "duplicate"
            )

        val second =
            event(
                id = "duplicate",
                hour = 10
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            ScheduleRevision.of(
                events =
                    listOf(
                        first,
                        second
                    ),
                zone = berlin
            )
        }
    }

    @Test
    fun currentRevisionMatchesScheduledRegistryForZone() {
        assertEquals(
            ScheduleRevision.of(
                events =
                    ScheduleRegistry.scheduledEvents,
                zone = berlin
            ),
            ScheduleRevision.current(
                zone = berlin
            )
        )
    }
}