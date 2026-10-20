# Alpha11 – Planungsbelege und Wiederanlauf je Geltungsbereich

Version `0.2.0-alpha11-test`, Versionscode 13, Testpaket `com.cztr.routinealarm.dev`.

## Umfang

Der bestehende Occurrence-Kern wird für ausdrücklich ausgewählte persönliche Ereignisse vorbereitet. Testwecker verwenden dieselben gemeinsamen Implementierungen; ihre Android-Adapter bleiben auf Tests begrenzt.

- Ein Scope wählt Tests oder genau eine persönliche Ereignis-ID.
- Ein Planungsversuch wird vor dem Plattformaufruf dauerhaft gespeichert. Seine Rückmeldung belegt nur den Aufruf, keine hörbare Ausgabe.
- Der Backup-Validator prüft persönliche Attempts gegen den historisch rekonstruierten Journalzustand.
- Wiederanlauf arbeitet in begrenzten Seiten und speichert seinen Fortschritt pro Scope, Zyklus und Restore-Epoche.
- Wiederhergestellte offene Einträge bleiben gesperrt, bis sie ausdrücklich aktiviert werden.

Die persönliche Wochenroutine nutzt weiterhin ihren bisherigen Scheduler und Ausgabepfad. Noch ausstehend sind autoritative Planablösung, persönliche Token-/Service-Anbindung und eine erreichbare Aktivierung wiederhergestellter persönlicher Einträge. Die neue Grundlage aktiviert keinen dieser Pfade automatisch.

## Lokale Prüfung

227 Tests in 28 Suiten bestanden, ohne Fehler oder Skips. Die 20 neuen Tests schlugen gegen das bisherige Test-only-Verhalten fehl und bestehen nach der Erweiterung. Ein weiterer Integrationstest prüft, dass der Android-Testadapter persönliche Einträge weder plant noch wiederherstellt oder ausgibt.

Geprüft werden außerdem 130 persönliche Einträge in 64/64/2 Schritten über Datenbank-Neustarts, Scopewechsel im gleichen Zyklus, echte ältere Checkpoint-Bytes, manipulierte historische Backup-Bindungen, Importpause, unbestätigte Plattformwirkungen sowie Datenbank- und Checkpointfehler. Die vollständige bisherige Suite bleibt enthalten. Debug-Build erfolgreich; Lint: 0 Fehler, 14 Warnungen.

## Kompatibilität

Datenbankschema 4 und Backup-Datenfelder bleiben erhalten. Alpha11 liest bisherige Backups. Ältere Versionen können ein Backup mit persönlichen Planungsversuchen ablehnen; dieser neue Inhalt ist nicht rückwärts lesbar.

Alte Recovery-Checkpoints gehören ausschließlich zum Test-Scope. Der neue Checkpoint bindet zusätzlich den Scope. Ein nicht lesbarer Checkpoint führt zu einem neuen Durchlauf; gespeicherte Occurrence-Zustände, Quarantäne und Zustellprüfungen bleiben maßgeblich.

## Handyprüfung

Die Nutzerbestätigung für Alpha10 gilt für Alpha10. Für Alpha11 sind die folgenden Prüfungen sinnvoll:

1. Über Alpha10 installieren; vorhandenen Verlauf prüfen.
2. Testwecker erstellen, App verlassen und das Display sperren.
3. Ausgabe, Verschieben und Erledigen prüfen.
4. Einen zukünftigen Testwecker anlegen, Handy neu starten und die Ausgabe prüfen.
5. Backup exportieren und dessen Wiederherstellung in einer leeren Testinstallation prüfen; importierte offene Tests müssen bis zur ausdrücklichen Aktivierung pausiert bleiben.

Ein erfolgreicher Hosttest ersetzt keine Prüfung von Lautstärke, Sprachausgabe, Doze oder OEM-Verhalten auf dem Gerät.
