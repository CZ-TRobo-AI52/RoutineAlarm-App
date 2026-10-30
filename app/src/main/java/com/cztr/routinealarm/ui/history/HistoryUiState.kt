package com.cztr.routinealarm.ui.history

data class HistoryUiState(
    val loadState: HistoryLoadState,
    val testTitle: String,
    val testDetail: String,
    val evidenceBoundary: String,
    val openCount: Int?,
    val showRetry: Boolean,
    val showPermissionAction: Boolean,
)
