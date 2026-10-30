package com.cztr.routinealarm.ui.backup

enum class BackupOperation { IDLE, EXPORTING, IMPORTING }

data class BackupUiInput(
    val busy: Boolean = false,
    val operation: BackupOperation = BackupOperation.IDLE,
    val statusMessage: String? = null,
)
