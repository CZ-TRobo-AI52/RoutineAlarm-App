package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.home.HomePlanState
import com.cztr.routinealarm.ui.home.HomeProjectedItem
import com.cztr.routinealarm.ui.home.HomeQuickAction
import com.cztr.routinealarm.ui.home.HomeUiInput
import com.cztr.routinealarm.ui.home.HomeUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeUiMapperBoundaryTest {
    private val now = ZonedDateTime.of(2026, 10, 30, 5, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    @Test fun mapperSortsAndBoundsAlreadyProjectedItemsWithoutReadingDomainState() {
        val projected = (8 downTo 1).map { index ->
            HomeProjectedItem("id-$index", "Routine $index", "Kategorie", now.plusMinutes(index.toLong()))
        }

        val result = HomeUiMapper.map(HomeUiInput(now, planEnabled = true, exactAllowed = true, projected))

        assertEquals(HomePlanState.PREVIEW, result.planState)
        assertEquals(listOf("id-1", "id-2", "id-3", "id-4", "id-5"), result.upcoming.map { it.id })
        assertEquals(listOf(HomeQuickAction.OPEN_HISTORY), result.quickActions)
    }

    @Test fun permissionStateKeepsProjectionButDoesNotClaimDelivery() {
        val result = HomeUiMapper.map(HomeUiInput(
            now,
            planEnabled = true,
            exactAllowed = false,
            projected = listOf(HomeProjectedItem("id", "Routine", "Morgen", now.plusMinutes(5))),
        ))

        assertEquals(HomePlanState.PERMISSION_REQUIRED, result.planState)
        assertTrue(result.detail.contains("nur eine Wochenplan-Vorschau"))
        assertEquals(HomeQuickAction.OPEN_SYSTEM, result.primaryAction)
    }
}
