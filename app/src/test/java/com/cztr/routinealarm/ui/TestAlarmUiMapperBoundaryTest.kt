package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.testalarm.*
import org.junit.Assert.*
import org.junit.Test

class TestAlarmUiMapperBoundaryTest {
    @Test fun activeLoadingAndHistoryLoadingRemainDistinct() {
        assertEquals("Testwecker wird geprüft …", TestAlarmUiMapper.loading(true).title)
        assertEquals("Testverlauf wird geladen …", TestAlarmUiMapper.loading(false).title)
    }

    @Test fun activeStateNeverTurnsTriggerTimestampIntoAudibleProof() {
        val state = TestAlarmUiMapper.active(
            title = "Test",
            spokenText = "Text",
            plannedText = "Geplant: 30.10. 07:00:00",
            triggeredText = "Ausgelöst: 30.10. 07:00:01",
            active = true,
        ) as TestAlarmUiState.Active
        assertTrue(state.evidenceBoundary.contains("keinen hörbaren Alarm"))
        assertEquals((1..5).toList(), state.snoozeMinutes)
    }

    @Test fun unavailableReadIsNotRenderedAsEmptyHistory() {
        val state = TestAlarmUiMapper.error() as TestAlarmUiState.Error
        assertTrue(state.message.contains("konnte nicht gelesen werden"))
        assertFalse(state.message.contains("Keine weiteren"))
    }

    @Test fun historyKeepsRestoreAndPermissionBoundaries() {
        val state = TestAlarmUiMapper.history(
            offset = 20,
            pausedCount = 2,
            exactAllowed = false,
            rows = emptyList(),
            hasNewer = true,
            hasOlder = false,
        ) as TestAlarmUiState.History
        assertTrue(state.intro.startsWith("Testwecker ab Eintrag 21"))
        assertTrue(state.pausedNotice!!.contains("früheren Gerät"))
        assertTrue(state.permissionNotice!!.contains("Genaue Alarme"))
    }
}
