package com.cztr.routinealarm.ui.history

enum class HistoryLoadState { LOADING, AVAILABLE, UNAVAILABLE }

data class HistoryUiInput(
    val loadState: HistoryLoadState,
    val openCount: Int?,
    val triggeredCount: Int?,
    val overdueCount: Int?,
    val nextText: String?,
    val exactAllowed: Boolean,
) {
    companion object {
        fun loading() = HistoryUiInput(HistoryLoadState.LOADING, null, null, null, null, true)
        fun unavailable() = HistoryUiInput(HistoryLoadState.UNAVAILABLE, null, null, null, null, true)
        fun available(
            openCount: Int,
            triggeredCount: Int,
            overdueCount: Int,
            nextText: String?,
            exactAllowed: Boolean,
        ) = HistoryUiInput(
            HistoryLoadState.AVAILABLE,
            openCount,
            triggeredCount,
            overdueCount,
            nextText,
            exactAllowed,
        )
    }
}
