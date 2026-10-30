package com.cztr.routinealarm.ui.backup

enum class BackupUiAction { SAVE, LOAD, OPEN_HISTORY, CLOSE }

data class BackupUiState(
    val title: String,
    val description: String,
    val securityWarning: String,
    val restoreBoundary: String,
    val status: String,
    val saveEnabled: Boolean,
    val loadEnabled: Boolean,
)
