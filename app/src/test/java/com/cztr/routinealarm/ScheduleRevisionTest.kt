package com.cztr.routinealarm

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScheduleRevisionTest {

    private fun event(
        id: String = "event-a",
        days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
        hour: Int = 8,
        minute: Int = 30,
        title: String = "Routine A",
        spokenText: String = title,
        category: EventCategory = EventCategory.PERSONAL,
        mode: EventMode = EventMode.ROUTINE,
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

    @Test
    fun sameLogicalScheduleAlwaysProducesSameRevision() {
        val first =
            listOf(
                event(id = "b"),
                event(
                    id = "a",
                    days = setOf(
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.MONDAY
                    ),
                    hour = 6,
                    minute = 15
                )
            )

        val reordered =
            first.reversed()

        assertEquals(
            ScheduleRevision.of(first),
            ScheduleRevision.of(reordered)
        )
    }

    @Test
    fun presentationChangesDoNotChangeRevision() {
        val original =
            event(
                title = "Aufstehen",
                spokenText = "Bitte aufstehen",
                category = EventCategory.MORNING
            )

        val renamed =
            original.copy(
                title = "Aufstehen und Wasser trinken",
                spokenText = "Jetzt aufstehen und Wasser trinken",
                category = EventCategory.PERSONAL
            )

        assertEquals(
            ScheduleRevision.of(listOf(original)),
            ScheduleRevision.of(listOf(renamed))
        )
    }

    @Test
    fun executionRelevantChangesProduceDifferentRevision() {
        val original =
            event()

        val variants =
            listOf(
                original.copy(
                    daysOfWeek = setOf(DayOfWeek.TUESDAY)
                ),
                original.copy(
                    hour = 9
                ),
                original.copy(
                    minute = 31
                ),
                original.copy(
                    mode = EventMode.ANNOUNCEMENT
                ),
                original.copy(
                    enabled = false
                )
            )

        val baseline =
            ScheduleRevision.of(
                listOf(original)
            )

        variants.forEach { changed ->
            assertNotEquals(
                baseline,
                ScheduleRevision.of(
                    listOf(changed)
                )
            )
        }
    }

    @Test
    fun changingStableEventIdChangesRevision() {
        val original =
            event(id = "routine-a")

        val changed =
            original.copy(
                id = "routine-b"
            )

        assertNotEquals(
            ScheduleRevision.of(
                listOf(original)
            ),
            ScheduleRevision.of(
                listOf(changed)
            )
        )
    }

    @Test
    fun duplicateEventIdsAreRejected() {
        val first =
            event(id = "duplicate")

        val second =
            event(
                id = "duplicate",
                hour = 10
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            ScheduleRevision.of(
                listOf(first, second)
            )
        }
    }

    @Test
    fun currentRevisionMatchesScheduledRegistry() {
        assertEquals(
            ScheduleRevision.of(
                ScheduleRegistry.scheduledEvents
            ),
            ScheduleRevision.current()
        )
    }
}