package com.cztr.routinealarm.ui

import com.cztr.routinealarm.*
import com.cztr.routinealarm.ui.home.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class HomeUiMapperBoundaryTest {
    private val now=ZonedDateTime.parse("2026-10-29T09:00:00+01:00[Europe/Berlin]")
    private fun event(id:String,hour:Int=10,enabled:Boolean=true,day:DayOfWeek=DayOfWeek.THURSDAY)=
        RoutineEvent(id,setOf(day),hour,0,id,category=EventCategory.PERSONAL,mode=EventMode.ROUTINE,enabled=enabled)
    @Test fun disabledEventsAreExcludedAndTiesAreStableBeforeFiveItemLimit() {
        val s=HomeUiMapper.map(now,true,true,listOf(event("disabled",9,false))+listOf("g","f","e","d","c","b","a").map { event(it) })
        assertEquals(listOf("a","b","c","d","e"),s.upcoming.map { it.id })
        assertEquals(Instant.parse("2026-10-29T09:00:00Z"),s.upcoming.first().projectedAt.toInstant())
    }
    @Test fun freshProjectionUpdatesTimestampAndRemainingMinutesTogether() {
        val first=HomeUiMapper.map(now,true,true,listOf(event("next")))
        val later=HomeUiMapper.map(now.plusMinutes(2),true,true,listOf(event("next")))
        assertEquals(now,first.projectedAt)
        assertEquals(now.plusMinutes(2),later.projectedAt)
        assertTrue(first.detail.contains("1 Std. 0 Min."))
        assertTrue(later.detail.contains("58 Minuten"))
        assertEquals(first.upcoming.single().projectedAt,later.upcoming.single().projectedAt)
    }
    @Test fun nextWeekIsUsedWhenTodayTimeHasPassed() {
        val s=HomeUiMapper.map(now,true,true,listOf(event("past",8)))
        assertEquals(LocalDate.parse("2026-11-05"),s.upcoming.single().projectedAt.toLocalDate())
    }
    @Test fun futureWeekdaySortsAfterTodaysRemainingEvent() {
        val s=HomeUiMapper.map(now,true,true,listOf(event("tomorrow",8,day=DayOfWeek.FRIDAY),event("today",10)))
        assertEquals(listOf("today","tomorrow"),s.upcoming.map { it.id })
    }
    @Test fun pauseDominatesPermissionAndHidesProjectedItems() {
        val s=HomeUiMapper.map(now,false,false,listOf(event("one")))
        assertEquals(HomePlanState.PAUSED,s.planState);assertTrue(s.upcoming.isEmpty())
    }
    @Test fun missingPermissionRetainsOnlyPreviewAndNeverClaimsReadiness() {
        val s=HomeUiMapper.map(now,true,false,listOf(event("one")))
        assertEquals(HomePlanState.PERMISSION_REQUIRED,s.planState);assertEquals(1,s.upcoming.size)
    }
    @Test fun emptyAndAllDisabledListsHaveNoNextItem() {
        for(events in listOf(emptyList(),listOf(event("disabled",enabled=false)))) {
            val s=HomeUiMapper.map(now,true,true,events)
            assertEquals(HomePlanState.EMPTY,s.planState);assertTrue(s.upcoming.isEmpty())
        }
    }
}
