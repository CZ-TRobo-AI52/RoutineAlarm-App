package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.backup.*
import org.junit.Assert.*
import org.junit.Test

class BackupUiMapperBoundaryTest {
    @Test fun idleStatePreservesSecurityAndRestoreBoundaries() {
        val state = BackupUiMapper.map(BackupUiInput())
        assertTrue(state.securityWarning.contains("unverschlüsselt"))
        assertTrue(state.restoreBoundary.contains("leeren Ereignisspeicher"))
        assertTrue(state.restoreBoundary.contains("persönliche Ereignisse"))
        assertTrue(state.saveEnabled)
        assertTrue(state.loadEnabled)
    }

    @Test fun exportingDisablesBothMutatingEntryPoints() {
        val state = BackupUiMapper.map(BackupUiInput(
            busy = true,
            operation = BackupOperation.EXPORTING,
        ))
        assertFalse(state.saveEnabled)
        assertFalse(state.loadEnabled)
        assertEquals("Sicherung wird geschrieben …", state.status)
    }

    @Test fun importingDisablesBothMutatingEntryPoints() {
        val state = BackupUiMapper.map(BackupUiInput(
            busy = true,
            operation = BackupOperation.IMPORTING,
        ))
        assertFalse(state.saveEnabled)
        assertFalse(state.loadEnabled)
        assertEquals("Sicherung wird geprüft …", state.status)
    }

    @Test fun explicitOutcomeMessageIsRenderedWithoutPromotion() {
        val state = BackupUiMapper.map(BackupUiInput(
            statusMessage = "Sicherung nicht bestätigt. Speicherort prüfen.",
        ))
        assertEquals("Sicherung nicht bestätigt. Speicherort prüfen.", state.status)
        assertFalse(state.status.contains("verifiziert", ignoreCase = true))
    }
}
