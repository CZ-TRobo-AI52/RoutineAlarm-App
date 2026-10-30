package com.cztr.routinealarm.ui.backup

/** Pure presentation mapping. Import/export authority and I/O remain in BackupActivity. */
object BackupUiMapper {
    fun map(input: BackupUiInput): BackupUiState {
        val status = input.statusMessage ?: when (input.operation) {
            BackupOperation.EXPORTING -> "Sicherung wird geschrieben …"
            BackupOperation.IMPORTING -> "Sicherung wird geprüft …"
            BackupOperation.IDLE ->
                "Bereit. Testwecker bleiben nach einem Import bis zur einzelnen Aktivierung pausiert."
        }
        return BackupUiState(
            title = "Ereignisverlauf sichern",
            description =
                "Sichert Testwecker und persönliche Ereignisse mit Verlauf, Planrevisionen und Planungsrückmeldungen. Android-Berechtigungen und tatsächlich registrierte Alarme sind nicht enthalten.",
            securityWarning =
                "Die Datei ist unverschlüsselt und kann Titel sowie gesprochene Texte enthalten. Wähle einen privaten Speicherort.",
            restoreBoundary =
                "Wiederherstellung ist nur in einen leeren Ereignisspeicher möglich. Testwecker werden einzeln aktiviert; persönliche Ereignisse bleiben in diesem Stand gesperrt.",
            status = status,
            saveEnabled = !input.busy,
            loadEnabled = !input.busy,
        )
    }
}
