package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.studio.StudioFilter
import com.cztr.routinealarm.ui.studio.StudioProjectedRoutine
import com.cztr.routinealarm.ui.studio.StudioUiInput
import com.cztr.routinealarm.ui.studio.StudioUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.DayOfWeek

class StudioUiMapperBoundaryTest {
    @Test fun activeFilterIsStableAndSelectedDetailsRemainReadOnly() {
        val state = StudioUiMapper.map(StudioUiInput(
            routines = listOf(
                routine("late", "Spät", 720, enabled = true),
                routine("paused", "Pausiert", 300, enabled = false),
                routine("early", "Früh", 360, enabled = true),
            ),
            filter = StudioFilter.ACTIVE,
            selectedId = "early",
        ))

        assertEquals(listOf("early", "late"), state.items.map { it.id })
        assertNotNull(state.selected)
        assertEquals("Nur ansehen", state.selected!!.authorityLabel)
        assertFalse(state.selected!!.detail.contains("gespeichert", ignoreCase = true))
    }

    @Test fun unknownSelectionDoesNotInventARoutine() {
        val state = StudioUiMapper.map(StudioUiInput(
            routines = listOf(routine("one", "Eins", 360, enabled = true)),
            filter = StudioFilter.ALL,
            selectedId = "missing",
        ))

        assertEquals(null, state.selected)
        assertEquals(1, state.items.size)
    }

    private fun routine(id: String, title: String, minute: Int, enabled: Boolean) = StudioProjectedRoutine(
        id = id,
        title = title,
        spokenText = "Sprich $title",
        minuteOfDay = minute,
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        enabled = enabled,
        categoryLabel = "Persönlich",
        modeLabel = "Routine",
    )
}
