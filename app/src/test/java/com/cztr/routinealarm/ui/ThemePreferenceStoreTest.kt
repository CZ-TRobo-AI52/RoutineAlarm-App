package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.shell.ThemeChoice
import com.cztr.routinealarm.ui.shell.ThemePreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceStoreTest {
    @Test fun storedChoiceSurvivesANewStoreInstance() {
        var persisted: String? = null
        ThemePreferenceStore({ persisted }, { persisted = it }).write(ThemeChoice.LIGHT)

        assertEquals(ThemeChoice.LIGHT, ThemePreferenceStore({ persisted }, { persisted = it }).read())
    }

    @Test fun unknownStoredValueFallsBackWithoutMutatingStorage() {
        var persisted: String? = "FUTURE_THEME"
        val store = ThemePreferenceStore({ persisted }, { persisted = it })

        assertEquals(ThemeChoice.SYSTEM, store.read(ThemeChoice.SYSTEM))
        assertEquals("FUTURE_THEME", persisted)
    }
}
