package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.alarm.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class AlarmUiMapperBoundaryTest {
    private val now = LocalDateTime.of(2026, 10, 30, 7, 5)

    @Test fun freshAuthorizedAlarmOffersAllFiveSnoozeDurations() {
        val state = AlarmUiMapper.map(AlarmUiInput(
            title = "Morgenroutine",
            spokenText = "Zeit für deine Morgenroutine",
            now = now,
            deliveryAllowed = true,
            pendingSnoozeMinutes = null,
            outputInstanceAvailable = true,
        ))

        assertEquals(listOf(1, 2, 3, 4, 5), state.snoozeMinutes)
        assertNull(state.pendingSnoozeMinutes)
    }

    @Test fun uncertainPreparedSnoozeOnlyOffersExactRetry() {
        val state = AlarmUiMapper.map(AlarmUiInput(
            title = "Routine",
            spokenText = "Routine",
            now = now,
            deliveryAllowed = true,
            pendingSnoozeMinutes = 3,
            outputInstanceAvailable = true,
        ))

        assertEquals(listOf(3), state.snoozeMinutes)
        assertEquals(3, state.pendingSnoozeMinutes)
        assertTrue(state.snoozeBoundary.contains("gleiche Dauer"))
    }

    @Test fun staleDeliveryCannotOfferSnooze() {
        val state = AlarmUiMapper.map(AlarmUiInput(
            title = "Routine",
            spokenText = "Routine",
            now = now,
            deliveryAllowed = false,
            pendingSnoozeMinutes = null,
            outputInstanceAvailable = true,
        ))

        assertTrue(state.snoozeMinutes.isEmpty())
        assertTrue(state.warning!!.contains("nicht mehr autorisiert"))
    }

    @Test fun closeOutputIsOnlyOfferedForIdentifiedOutputInstance() {
        val unavailable = AlarmUiMapper.map(AlarmUiInput(
            "Routine", "Routine", now, true, null, false,
        ))
        val available = AlarmUiMapper.map(AlarmUiInput(
            "Routine", "Routine", now, true, null, true,
        ))

        assertFalse(unavailable.showCloseOutput)
        assertTrue(available.showCloseOutput)
        assertTrue(available.evidenceBoundary.contains("persistente persönliche"))
    }

    @Test fun clockAndDateArePurePresentationProjection() {
        val state = AlarmUiMapper.map(AlarmUiInput(
            "Routine", "Text", now, true, null, true,
        ))
        assertEquals("07:05", state.clockText)
        assertTrue(state.dateText.contains("30."))
    }
}
