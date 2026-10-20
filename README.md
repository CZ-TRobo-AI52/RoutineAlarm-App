# RoutineAlarm-App

Native Android-App für Routinen, Wecker und gesprochene Erinnerungen.

## Alpha10: Trainingsabbrüche und Sitzungswechsel absichern

Entwicklungsstand `0.2.0-alpha10-test`, Versionscode **12**, Paket `com.cztr.routinealarm.dev`, Android 8 oder neuer.

Leere Trainingssitzungen beenden sich sofort. Verspätete Sprachinitialisierung startet keine abgebrochene Sitzung erneut; der Abschluss einer alten Ansage kann keine neue Sitzung mehr beenden. Sitzungswechsel stoppen die vorherige Sprachausgabe und entfernen ihre geplanten Ansagen.

Der vorhandene Testalarm-Coordinator verwendet jetzt gemeinsame Zustellprüfungen für Geltungsbereich, Restore-Epoche, Generation und Revision. Tests prüfen diesen Vertrag auch mit gespeicherten persönlichen Routinen. Die Wochenroutine verwendet weiterhin ihren bestehenden Ausgabepfad; ihre Migration erfordert zusätzliche Scheduling-, Restore- und Recovery-Arbeit.

- [Alpha10: Änderungen, Grenzen und Handyprüfung](docs/ALPHA10.md)
- [Allgemeine Handy-Testanleitung](docs/PHONE_TEST.md)

### Alpha10-Validierung

**206 Tests in 26 Suites bestanden**, ohne Fehler, Fehlschläge oder übersprungene Tests. Zehn neue Diensttests sichern Training ab, neun neue SQLite-Tests den gemeinsamen Zustellvertrag. Drei Trainingsfehler wurden vor der Korrektur durch fehlgeschlagene Tests reproduziert. Gesamtsuite, Debug-Build und Lint laufen erfolgreich; Lint meldet 0 Fehler und 14 bestehende Warnungen. Der unabhängige Code-Review fand keine funktionalen Befunde.

Ein Alpha10-Realgerätetest steht aus. Die Hosttests verwenden kontrollierte Sprachrückmeldungen und ersetzen keine hörbare Prüfung auf dem Handy.

## Vorheriger Stand: Alpha09

Die Teststatus-Karte und der Testverlauf lesen ihre Daten jetzt auf einem Hintergrund-Worker. Währenddessen zeigen sie einen Ladezustand. Ältere Ergebnisse können eine neuere Ansicht nicht mehr überschreiben; Lesefehler werden mit einer Möglichkeit zum erneuten Laden angezeigt.

Die Statuskarte lädt Zähler und höchstens den nächsten Testwecker. Der Verlauf zeigt höchstens 20 Tests pro Seite samt letzter Planungsrückmeldung. Beide Ansichten verwenden einen konsistenten Datenbankstand. Schema 4, Backupformat, Importpause und gespeicherter Verlauf bleiben erhalten.

- [Änderungen, Grenzen und Handyprüfung für Alpha09](docs/ALPHA09-RESPONSIVE-HISTORY.md)
- [Allgemeine Handy-Testanleitung](docs/PHONE_TEST.md)
- [Vorheriger Stand: Hintergrund-Recovery in Alpha08](docs/ALPHA08.md)

Alpha09 betrifft lesende UI-Zugriffe. Erledigen, Verschieben, Aktivieren und Erstellen bleiben im bisherigen synchronen Ablauf mit der Alarmausgabe. Wochenplan, Training und Aufbewahrungsregeln sind weiterhin eigene Entwicklungsschritte.

## Alpha09-Validierung

Alpha09 besteht mit **182 Tests in 23 Suites**, ohne Fehler, Fehlschläge oder übersprungene Tests. Enthalten sind 21 neue Regressionstests: acht für SQL-Lesemodelle, fünf für den Loader und acht für die asynchronen Ansichten. Android Lint: 0 Fehler, 14 Warnungen lokal; Debug-APK gebaut. Auch die frische Quellarchiv-Prüfung und [Linux-CI](https://github.com/CZ-TRobo-AI52/RoutineAlarm-App/actions/runs/39322113346) bestehen; das [Testrelease](https://github.com/CZ-TRobo-AI52/RoutineAlarm-App/releases/tag/v0.2.0-alpha09-test) ist veröffentlicht.

Am 20.10.2026 bestätigt der Nutzer alle erfragten Alpha09-Tests auf **Seeker / Android 16**: Upgrade, Alarm bei gesperrtem Display, Sprache, Erledigen/Snooze und Neustart. Das ist ein Nutzerbericht (`USER_REPORTED_REAL_DEVICE_PASS`); unabhängige Instrumentierung und Rohlogs liegen nicht vor.

## Vorherige Charakterisierung der Weekly-Übergabe

Fünf zusätzliche Regressionstests sichern die bestehende Übergabe vom Wochenalarm an die Ausgabedienste: alle sechs Trainingsstarts, erneute Planung derselben Alarmidentität, aktuelle Routine-Texte sowie wirkungslose pausierte, ungültige und deaktivierte Zustellungen. Sie verwenden echte Scheduler-PendingIntents und den produktiven Receiver unter Robolectric.

Dieser vorherige Charakterisierungsschritt bestand mit **187 Tests in 24 Suites**, ohne Fehler oder übersprungene Tests; Lint meldete 0 Fehler und 14 Warnungen. Sein Debug-Build war bytegleich mit dem veröffentlichten Alpha09-APK. Diese fünf Tests ergänzten ausschließlich Tests und Dokumentation. Die Migration von Wochenplan und Training auf die vorhandene Occurrence-Engine folgt schrittweise.
