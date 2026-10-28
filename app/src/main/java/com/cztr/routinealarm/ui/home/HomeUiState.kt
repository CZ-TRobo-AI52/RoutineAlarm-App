package com.cztr.routinealarm.ui.home

import java.time.ZonedDateTime

enum class HomePlanState { PAUSED, PERMISSION_REQUIRED, EMPTY, PREVIEW }

data class HomeUpcomingItem(val id: String, val title: String, val timeLabel: String,
    val categoryLabel: String, val projectedAt: ZonedDateTime)

/** A weekly-plan projection. It contains no receipt or user-completion assertions. */
data class HomeUiState(val greeting: String, val planState: HomePlanState,
    val title: String, val detail: String, val upcoming: List<HomeUpcomingItem>)
