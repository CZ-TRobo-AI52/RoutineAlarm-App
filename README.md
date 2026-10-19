# RoutineAlarm-App

Native Android-App für Routinen, Wecker und gesprochene Erinnerungen.

## Alpha09: Teststatus und Verlauf im Hintergrund laden

Entwicklungsstand `0.2.0-alpha09-test`, Versionscode **11**, Paket `com.cztr.routinealarm.dev`, Android 8 oder neuer.

Die Teststatus-Karte und der Testverlauf lesen ihre Daten jetzt auf einem Hintergrund-Worker. Währenddessen zeigen sie einen Ladezustand. Ältere Ergebnisse können eine neuere Ansicht nicht mehr überschreiben; Lesefehler werden mit einer Möglichkeit zum erneuten Laden angezeigt.

Die Statuskarte lädt Zähler und höchstens den nächsten Testwecker. Der Verlauf zeigt höchstens 20 Tests pro Seite samt letzter Planungsrückmeldung. Beide Ansichten verwenden einen konsistenten Datenbankstand. Schema 4, Backupformat, Importpause und gespeicherter Verlauf bleiben erhalten.

- [Änderungen, Grenzen und Handyprüfung für Alpha09](docs/ALPHA09-RESPONSIVE-HISTORY.md)
- [Allgemeine Handy-Testanleitung](docs/PHONE_TEST.md)
- [Vorheriger Stand: Hintergrund-Recovery in Alpha08](docs/ALPHA08.md)

Alpha09 betrifft lesende UI-Zugriffe. Erledigen, Verschieben, Aktivieren und Erstellen bleiben im bisherigen synchronen Ablauf mit der Alarmausgabe. Wochenplan, Training und Aufbewahrungsregeln sind weiterhin eigene Entwicklungsschritte.

## Validierung

Die lokale Gesamtsuite besteht mit **182 Tests in 23 Suites**, ohne Fehler, Fehlschläge oder übersprungene Tests. Enthalten sind 21 neue Regressionstests: acht für SQL-Lesemodelle, fünf für den Loader und acht für die asynchronen Ansichten. Android Lint: 0 Fehler, 14 Warnungen; Debug-APK gebaut. Frischer Quellarchiv-Build, CI und Veröffentlichung werden separat nachgewiesen. Für Alpha09 wurde noch kein Realgerätetest durchgeführt.
