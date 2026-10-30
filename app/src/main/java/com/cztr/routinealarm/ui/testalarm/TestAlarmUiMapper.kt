package com.cztr.routinealarm.ui.testalarm

/** Pure presentation rules for the test-alarm screen. */
object TestAlarmUiMapper {
    fun loading(active: Boolean): TestAlarmUiState.Loading = TestAlarmUiState.Loading(
        if (active) "Testwecker wird geprüft …" else "Testverlauf wird geladen …"
    )

    fun invalid() = TestAlarmUiState.Invalid(
        "Ungültiger Testwecker",
        "Die Alarmidentität fehlt oder wurde verändert.",
    )

    fun error() = TestAlarmUiState.Error(
        "Der Testverlauf konnte nicht gelesen werden. Die Daten bleiben erhalten."
    )

    fun active(
        title: String,
        spokenText: String?,
        plannedText: String?,
        triggeredText: String?,
        active: Boolean,
    ) = TestAlarmUiState.Active(
        title = title,
        spokenText = spokenText?.takeIf { it.isNotBlank() && it != title },
        timing = listOfNotNull(plannedText, triggeredText).joinToString("\n").ifBlank { null },
        active = active,
        snoozeMinutes = if (active) (1..5).toList() else emptyList(),
        evidenceBoundary = if (active)
            "Der gespeicherte Auslösezeitpunkt bestätigt einen Zustandsübergang, aber keinen hörbaren Alarm."
        else
            "Die gespeicherte Entscheidung bleibt erhalten; dieser Testwecker besitzt keine aktuelle Aktionsautorität.",
    )

    fun history(
        offset: Int,
        pausedCount: Int,
        exactAllowed: Boolean,
        rows: List<TestHistoryUiRow>,
        hasNewer: Boolean,
        hasOlder: Boolean,
    ) = TestAlarmUiState.History(
        intro = "Testwecker ab Eintrag ${offset + 1}, höchstens 20 je Seite. Sie zählen nicht als persönliche Routinen.",
        pausedNotice = pausedCount.takeIf { it > 0 }?.let {
            "$it übernommene Ereignisse sind pausiert. Gesicherte Planungsrückmeldungen gehören zum früheren Gerät bzw. Datenstand und bestätigen keine aktuelle Planung."
        },
        generalNotice =
            "Zukünftige Testwecker werden bei App-Rückkehr und Neustart erneut geprüft. Offene und überfällige Tests können manuell geplant oder geöffnet werden; vergangene Zeiten können beim erneuten Planen sofort auslösen.",
        permissionNotice = if (exactAllowed) null else
            "Genaue Alarme sind nicht erlaubt. Gespeicherte Tests bleiben erhalten.",
        emptyNotice = if (rows.isEmpty()) "Keine weiteren Testwecker vorhanden." else null,
        rows = rows,
        hasNewer = hasNewer,
        hasOlder = hasOlder,
    )
}
