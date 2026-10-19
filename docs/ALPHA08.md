# Alpha08: Testwecker im Hintergrund wiederherstellen

Testversion `0.2.0-alpha08-test`, Versionscode 10, Paket `com.cztr.routinealarm.dev`.
Ausgangspunkt ist Alpha07, Commit `0b793a89be8afb02c3aee4f30c003cbd2616cf9c`.

## Änderung

Startansicht, Testverlauf und Systemereignisse fordern die automatische Testwecker-Recovery über Androids JobScheduler an. Sie warten dabei nicht mehr auf die gesamte Alarmhistorie. Ein einzelner Hintergrund-Worker bearbeitet zuerst zukünftige Wecker, danach bekannte alte Kennungen. Jede Portion prüft höchstens 64 Kandidaten; ein Job kann mehrere Portionen ausführen.

Ein atomar geschriebener Fortschrittsstand enthält nur Zyklus, Epoche, Phase und Suchposition. Er liegt im vom Backup ausgeschlossenen App-Verzeichnis. Nach Prozessabbruch oder Job-Stop kann derselbe Zyklus fortgesetzt werden. Nach einem Neustart des Geräts beginnt ein neuer Zyklus, weil Android die bisherigen Alarmregistrierungen verloren hat. Eine neue Restore-Epoche verwirft alte Fortschrittsstände ebenfalls.

Noch wartende Anforderungen werden zusammengefasst. Eine Anforderung während laufender Verarbeitung erhält einen nachfolgenden Zyklus. Androids Work-Queue übernimmt den Abschluss; dadurch wird eine gleichzeitig neu eintreffende Anforderung nicht durch einen verspäteten Abschlussaufruf gelöscht. Bei Datenbank-/Dateifehlern bleibt unfertige Arbeit für einen späteren Versuch erhalten.

Schema 4, Sicherungsformat, Importpause und gespeichertes Execution Journal bleiben erhalten. Wiederherstellung bestätigt weiterhin nur Planungsaufrufe, keine tatsächliche Zustellung.

## Handyprüfung

1. Alpha08 über die gleich signierte Alpha07-Testinstallation installieren. Verlauf und Importpause müssen erhalten bleiben.
2. Bei pausiertem Wochenplan mehrere Testwecker anlegen. Zwischen Startansicht und Testverlauf wechseln; beide Ansichten sollen bedienbar bleiben.
3. Einen Test für eine ausreichend spätere Uhrzeit behalten und das Gerät neu starten. Nach dem Start die tatsächliche Alarm-/Sprachausgabe beobachten. Android bestimmt, wann die Hintergrund-Recovery laufen darf.
4. Exakte Alarme vorübergehend entziehen und wieder erlauben. Den gespeicherten Zustand und die späteren Planungsrückmeldungen prüfen.
5. Verschieben, Erledigen, überfällige manuelle Wiederholung und pausierte importierte Tests wie in [Alpha07](ALPHA07.md) und [Alpha06](ALPHA06.md) prüfen.

Gerätemodell, Androidversion, Uhrzeit und Beobachtung festhalten. Kein Realgerätetest wurde für diese Version durchgeführt. Die [allgemeine Telefonprüfung](PHONE_TEST.md) bleibt erforderlich.

## Grenzen

- 64 ist eine Grenze pro Verarbeitungsschritt, keine garantierte Laufzeit. Einzelne SQLite-/Binder-Aufrufe können länger dauern; Stop wird zwischen Schritten geprüft.
- Hintergrundausführung unterliegt Androids Energie-, Quoten- und Startregeln. Ein kurz bevorstehender Wecker kann vor der Recovery fällig werden. Überfällige Tests starten weiterhin nur auf ausdrückliche Benutzeraktion.
- Die gesamte historische Bereinigung wächst weiter mit der Datenbank. Wiederholte vollständige Zyklen erzeugen Planungsbelege; eine Aufbewahrungsregel steht aus.
- Wochenplanplanung, einzelne Benutzeraktionen und das Laden der Statuskarte sind noch nicht vollständig vom UI-Thread ausgelagert. Wochenplan und Training verwenden weiter ihren bisherigen Ausgabepfad.
- Dies ist ein weiterer Teil von M2.2b. Vollständige Migration, kanonische Robo-AI52-Anbindung und Geräteabnahme bleiben offen.

Tests, frischer Quellarchiv-Build, APK-Signatur, Prüfsummen und CI werden im Lieferbericht getrennt dokumentiert. Hintergrundverhalten auf einem echten Gerät wird durch Hosttests nicht nachgewiesen.

Lokale Erstprüfung: 161 Tests in 20 Suites bestanden, keine Fehler oder übersprungenen Tests; Lint ohne Fehler mit 14 bestehenden Warnungen, Debug-APK gebaut. Der Boot-Einstieg wurde unter Robolectric für API 26, 31 und 35 ausgeführt. Test-Shadows bilden die Job-Queue-/Binder-Grenze ab; die App-Verarbeitung, native SQLite und Android-Adapter laufen dabei tatsächlich. Ein Windows-spezifischer Test-Shim ersetzt die dort abweichende Datei-Rename-Operation; unter Linux bleibt die Android-Framework-Implementierung aktiv.

## Plattformvertrag

[JobScheduler.enqueue](https://developer.android.com/reference/android/app/job/JobScheduler#enqueue(android.app.job.JobInfo,android.app.job.JobWorkItem)) hält Arbeit auch bei einem bereits laufenden Job. [JobParameters.dequeueWork](https://developer.android.com/reference/android/app/job/JobParameters#dequeueWork()) übernimmt den normalen Abschluss. Nicht abgeschlossene Arbeit wird bei `onStopJob = true` erneut angeboten. Work Items bleiben für Android-8-Kompatibilität ohne Neustartpersistenz; der Boot-Receiver fordert stattdessen einen frischen Zyklus an.
