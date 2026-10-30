package com.cztr.routinealarm.ui.studio

data class StudioRoutineItem(
    val id: String,
    val title: String,
    val timeLabel: String,
    val daysLabel: String,
    val statusLabel: String,
    val categoryLabel: String,
)

data class StudioRoutineDetail(
    val id: String,
    val title: String,
    val timeLabel: String,
    val daysLabel: String,
    val statusLabel: String,
    val categoryLabel: String,
    val modeLabel: String,
    val spokenText: String,
    val authorityLabel: String,
    val detail: String,
)

data class StudioUiState(
    val filter: StudioFilter,
    val filters: List<StudioFilter>,
    val items: List<StudioRoutineItem>,
    val selected: StudioRoutineDetail?,
)
