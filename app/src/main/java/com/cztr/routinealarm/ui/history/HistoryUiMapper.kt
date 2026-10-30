package com.cztr.routinealarm.ui.history

/** Pure history projection. Missing data is unavailable, never an empty history claim. */
object HistoryUiMapper {
    fun map(input: HistoryUiInput): HistoryUiState = when (input.loadState) {
        HistoryLoadState.LOADING -> HistoryUiState(
            loadState = input.loadState,
            testTitle = "Testverlauf wird geladen …",
            testDetail = "Gespeicherte Testwecker werden außerhalb des UI-Threads gelesen.",
            evidenceBoundary = "Noch kein Ergebnis verfügbar.",
            openCount = null,
            showRetry = false,
            showPermissionAction = false,
        )
        HistoryLoadState.UNAVAILABLE -> HistoryUiState(
            loadState = input.loadState,
            testTitle = "Verlauf nicht verfügbar",
            testDetail = "Die gespeicherten Daten konnten nicht gelesen werden. Sie werden nicht als leer dargestellt.",
            evidenceBoundary = "Die Daten bleiben erhalten; erst ein erfolgreicher Read darf Zähler anzeigen.",
            openCount = null,
            showRetry = true,
            showPermissionAction = false,
        )
        HistoryLoadState.AVAILABLE -> {
            val detail = buildString {
                append("${input.openCount} offen · ${input.triggeredCount} ausgelöst · ${input.overdueCount} überfällig")
                input.nextText?.let { append("\n$it") }
            }
            HistoryUiState(
                loadState = input.loadState,
                testTitle = "Testwecker",
                testDetail = detail,
                evidenceBoundary = "Ausgelöst bedeutet gespeicherter Triggerzustand und bestätigt keine hörbare Ausgabe oder erledigte Handlung.",
                openCount = input.openCount,
                showRetry = false,
                showPermissionAction = !input.exactAllowed,
            )
        }
    }
}
