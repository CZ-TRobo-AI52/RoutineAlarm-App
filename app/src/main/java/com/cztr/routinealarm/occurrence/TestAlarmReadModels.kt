package com.cztr.routinealarm.occurrence

data class TestAlarmStatusSummary(
    val openCount: Int,
    val triggeredCount: Int,
    val overdueCount: Int,
    val nextTest: RoutineOccurrence?,
)

data class TestAlarmHistoryItem(
    val occurrence: RoutineOccurrence,
    val latestSchedulingAttempt: SchedulingAttempt?,
    val deliveryAllowed: Boolean,
)

data class TestAlarmHistoryPage(
    val items: List<TestAlarmHistoryItem>,
    val deliveryEpoch: String,
    val pausedCount: Int,
)
