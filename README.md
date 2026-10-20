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

Alpha09 besteht mit **182 Tests in 23 Suites**, ohne Fehler, Fehlschläge oder übersprungene Tests. Enthalten sind 21 neue Regressionstests: acht für SQL-Lesemodelle, fünf für den Loader und acht für die asynchronen Ansichten. Android Lint: 0 Fehler, 14 Warnungen lokal; Debug-APK gebaut. Auch die frische Quellarchiv-Prüfung und [Linux-CI](https://github.com/CZ-TRobo-AI52/RoutineAlarm-App/actions/runs/39322113346) bestehen; das [Testrelease](https://github.com/CZ-TRobo-AI52/RoutineAlarm-App/releases/tag/v0.2.0-alpha09-test) ist veröffentlicht.

Am 20.10.2026 bestätigt der Nutzer alle erfragten Alpha09-Tests auf **Seeker / Android 16**: Upgrade, Alarm bei gesperrtem Display, Sprache, Erledigen/Snooze und Neustart. Das ist ein Nutzerbericht (`USER_REPORTED_REAL_DEVICE_PASS`); unabhängige Instrumentierung und Rohlogs liegen nicht vor.

## Vorbereitung der gemeinsamen Ausführung

Fünf zusätzliche Regressionstests sichern die bestehende Übergabe vom Wochenalarm an die Ausgabedienste: alle sechs Trainingsstarts, erneute Planung derselben Alarmidentität, aktuelle Routine-Texte sowie wirkungslose pausierte, ungültige und deaktivierte Zustellungen. Sie verwenden echte Scheduler-PendingIntents und den produktiven Receiver unter Robolectric.

Die vollständige lokale Suite besteht jetzt mit **187 Tests in 24 Suites**, ohne Fehler oder übersprungene Tests; Lint meldet 0 Fehler und 14 Warnungen. Der Debug-Build ist bytegleich mit dem veröffentlichten Alpha09-APK. Diese Änderung ergänzt Tests und Dokumentation. Service-Lifecycle, tatsächliche Android-Zustellung und Sprachausgabe sind durch die neuen Hosttests nicht nachgewiesen. Die Migration von Wochenplan und Training auf die vorhandene Occurrence-Engine folgt als separater Entwicklungsschritt.
