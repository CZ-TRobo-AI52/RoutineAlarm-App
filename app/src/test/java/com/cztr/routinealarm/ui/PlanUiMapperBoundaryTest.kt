package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.plan.PlanAvailability
import com.cztr.routinealarm.ui.plan.PlanProjectedItem
import com.cztr.routinealarm.ui.plan.PlanUiInput
import com.cztr.routinealarm.ui.plan.PlanUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek

class PlanUiMapperBoundaryTest {
    @Test fun selectedDayContainsOnlyEnabledSortedItemsAndNoDeliveryClaims() {
        val input = PlanUiInput(
            planEnabled = true,
            exactAllowed = true,
            activeEventCount = 3,
            selectedDay = DayOfWeek.MONDAY,
            events = listOf(
                item("late", "Spät", 720, setOf(DayOfWeek.MONDAY)),
                item("disabled", "Aus", 300, setOf(DayOfWeek.MONDAY), enabled = false),
                item("other", "Dienstag", 100, setOf(DayOfWeek.TUESDAY)),
                item("early", "Früh", 360, setOf(DayOfWeek.MONDAY)),
            ),
            training = emptyList(),
            showTrainingDetails = false,
        )

        val state = PlanUiMapper.map(input)

        assertEquals(PlanAvailability.ACTIVE, state.availability)
        assertEquals(listOf("early", "late"), state.events.map { it.id })
        assertFalse(state.detail.contains("zugestellt", ignoreCase = true))
        assertFalse(state.detail.contains("erledigt", ignoreCase = true))
    }

    @Test fun permissionMissingRemainsAProjectionWithoutChangingPlanState() {
        val state = PlanUiMapper.map(PlanUiInput(
            planEnabled = true,
            exactAllowed = false,
            activeEventCount = 1,
            selectedDay = DayOfWeek.MONDAY,
            events = listOf(item("one", "Routine", 360, setOf(DayOfWeek.MONDAY))),
            training = emptyList(),
            showTrainingDetails = false,
        ))

        assertEquals(PlanAvailability.PERMISSION_REQUIRED, state.availability)
        assertEquals("System prüfen", state.primaryActionLabel)
        assertEquals(1, state.events.size)
    }

    private fun item(
        id: String,
        title: String,
        minute: Int,
        days: Set<DayOfWeek>,
        enabled: Boolean = true,
    ) = PlanProjectedItem(id, title, minute, days, enabled, "Persönlich")
}
