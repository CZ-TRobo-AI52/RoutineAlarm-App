package com.cztr.routinealarm.ui

import com.cztr.routinealarm.ui.home.HomePlanState
import com.cztr.routinealarm.ui.home.HomeProjectedItem
import com.cztr.routinealarm.ui.home.HomeQuickAction
import com.cztr.routinealarm.ui.home.HomeUiInput
import com.cztr.routinealarm.ui.home.HomeUiMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.cztr.routinealarm.*
import com.cztr.routinealarm.ui.home.*
import java.time.*
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

    private val c1Now=ZonedDateTime.parse("2026-10-29T09:00:00+01:00[Europe/Berlin]")
    private fun event(id:String,hour:Int=10,enabled:Boolean=true,day:DayOfWeek=DayOfWeek.THURSDAY)=
        RoutineEvent(id,setOf(day),hour,0,id,category=EventCategory.PERSONAL,mode=EventMode.ROUTINE,enabled=enabled)
    @Test fun disabledEventsAreExcludedAndTiesAreStableBeforeFiveItemLimit() {
        val s=project(c1Now,true,true,listOf(event("disabled",9,false))+listOf("g","f","e","d","c","b","a").map { event(it) })
        assertEquals(listOf("a","b","c","d","e"),s.upcoming.map { it.id })
        assertEquals(Instant.parse("2026-10-29T09:00:00Z"),s.upcoming.first().projectedAt.toInstant())
    }
    @Test fun freshProjectionUpdatesTimestampAndRemainingMinutesTogether() {
        val first=project(c1Now,true,true,listOf(event("next")))
        val later=project(c1Now.plusMinutes(2),true,true,listOf(event("next")))
        assertEquals(c1Now,first.projectedAt)
        assertEquals(c1Now.plusMinutes(2),later.projectedAt)
        assertTrue(first.detail.contains("1 Std. 0 Min."))
        assertTrue(later.detail.contains("58 Minuten"))
        assertEquals(first.upcoming.single().projectedAt,later.upcoming.single().projectedAt)
    }
    @Test fun nextWeekIsUsedWhenTodayTimeHasPassed() {
        val s=project(c1Now,true,true,listOf(event("past",8)))
        assertEquals(LocalDate.parse("2026-11-05"),s.upcoming.single().projectedAt.toLocalDate())
    }
    @Test fun futureWeekdaySortsAfterTodaysRemainingEvent() {
        val s=project(c1Now,true,true,listOf(event("tomorrow",8,day=DayOfWeek.FRIDAY),event("today",10)))
        assertEquals(listOf("today","tomorrow"),s.upcoming.map { it.id })
    }
    @Test fun pauseDominatesPermissionAndHidesProjectedItems() {
        val s=project(c1Now,false,false,listOf(event("one")))
        assertEquals(HomePlanState.PAUSED,s.planState);assertTrue(s.upcoming.isEmpty())
    }
    @Test fun missingPermissionRetainsOnlyPreviewAndNeverClaimsReadiness() {
        val s=project(c1Now,true,false,listOf(event("one")))
        assertEquals(HomePlanState.PERMISSION_REQUIRED,s.planState);assertEquals(1,s.upcoming.size)
    }
    @Test fun emptyAndAllDisabledListsHaveNoNextItem() {
        for(events in listOf(emptyList(),listOf(event("disabled",enabled=false)))) {
            val s=project(c1Now,true,true,events)
            assertEquals(HomePlanState.EMPTY,s.planState);assertTrue(s.upcoming.isEmpty())
        }
    }

    private fun project(now: ZonedDateTime, enabled: Boolean, exact: Boolean, events: List<RoutineEvent>) =
        HomeUiMapper.map(HomeProjectionSource.from(now, enabled, exact, events))
}
