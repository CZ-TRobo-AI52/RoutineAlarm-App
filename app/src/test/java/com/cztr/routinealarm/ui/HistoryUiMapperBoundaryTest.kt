package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.history.HistoryLoadState
import com.cztr.routinealarm.ui.history.HistoryUiInput
import com.cztr.routinealarm.ui.history.HistoryUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryUiMapperBoundaryTest {
    @Test fun unavailableReadIsNotRenderedAsZeroHistory() {
        val state = HistoryUiMapper.map(HistoryUiInput.unavailable())

        assertEquals(HistoryLoadState.UNAVAILABLE, state.loadState)
        assertNull(state.openCount)
        assertTrue(state.showRetry)
        assertFalse(state.testDetail.contains("0 offen"))
    }

    @Test fun triggeredCountNeverClaimsAudibleDelivery() {
        val state = HistoryUiMapper.map(HistoryUiInput.available(
            openCount = 3,
            triggeredCount = 2,
            overdueCount = 1,
            nextText = "Nächster Test morgen",
            exactAllowed = true,
        ))

        assertEquals(HistoryLoadState.AVAILABLE, state.loadState)
        assertTrue(state.testDetail.contains("2 ausgelöst"))
        assertTrue(state.evidenceBoundary.contains("keine hörbare Ausgabe"))
    }
}
