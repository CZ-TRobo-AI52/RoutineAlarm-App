# Alpha12: Planhistorie sichern, persönliche Importe sperren

Version `0.2.0-alpha12-test`, Versionscode 14, Paket `com.cztr.routinealarm.dev`.
Begrenzter Zwischenbuild auf den Nutzerauftrag vom 21.10.2026: soweit möglich
fertigstellen und anschließend auf dem Handy testen. Der 52-Gate-Gesamtplan ist offen.

## Änderungen

- Backupformat 2 erhält zusätzlich die gespeicherten Planrevisionen und Stilllegungen.
- Alte Sicherungen bleiben importierbar; ihnen wird keine persönliche Plan-Autorität hinzugefügt.
- Restore schreibt vollständig oder gar nicht, erzeugt eine neue Zustellungsepoche
  und pausiert offene Ereignisse. Vorhandener Verlauf wird nicht überschrieben.
- Der bisherige Aktivierungsweg gibt ausschließlich Testwecker frei. Persönliche
  Importe bleiben gesperrt, auch nach erneutem Öffnen und trotz bekannter Planrevision.
- Die Sicherungsansicht erklärt Umfang, Klartextdatei und Versionskompatibilität.

## Grenzen

Der bisherige Wochen- und Trainingsablauf bleibt erhalten. Die persönliche Migration
auf den gemeinsamen Ausführungskern, persönliche Restore-Aktivierung, Output-Arbiter,
verschlüsselte Backups und Robo-AI52-Dialog sind noch offen. Ein gespeicherter Fingerprint
belegt keine authentische oder vollständige Herkunft. Deshalb gibt dieser Build
importierte persönliche Pläne nicht zur Ausführung frei.

Der Build enthält keine neuen Berechtigungen. Neue Backupdateien benötigen Alpha12
oder neuer. Frühere Handyabnahmen gelten ausschließlich für die früheren Builds.

## Begrenzter Handycheck

1. APK als Update der bestehenden **RoutineAlarm Test** installieren. Bei
   Signaturfehler abbrechen; die vorhandene App und ihren Verlauf nicht löschen.
2. App-Version prüfen, Startansicht und Tages-/Trainingsübersicht öffnen.
3. Unter **System & Test** einen Ein-Minuten-Testwecker starten, Display sperren:
   Alarm und Sprache getrennt beobachten. Erledigen, Überspringen und Snooze
   mit jeweils einem eigenen Test prüfen.
4. Einen zukünftigen Testwecker anlegen, Handy neu starten und die Auslösung prüfen.
5. **Ereignisverlauf sichern / laden** öffnen und eine Sicherung speichern.
   Die Datei darf Titel und gesprochene Texte enthalten.
6. Eine Rücksicherung in denselben belegten Verlauf muss ohne Datenverlust abgelehnt
   werden. Den vorhandenen Verlauf dafür nicht zurücksetzen.
7. Nur falls ein separates leeres Testprofil vorhanden ist: dort dieselbe APK
   installieren, Sicherung laden und prüfen, dass Tests pausiert bleiben. Einen
   zukünftigen Test ausdrücklich aktivieren. Persönliche Importe bleiben gesperrt.

Für jeden Fall Modell, Android-Version, APK-Hash, Datum, Berechtigungen, erwartetes
und beobachtetes Verhalten sowie PASS/FAIL festhalten. Ohne separates Profil bleibt
Fall 7 offen. Keine Geräte-, Audio-, Akku- oder Doze-Zuverlässigkeit wird vorweggenommen.

## Reproduktion

JDK 21, Gradle 8.9, Android SDK 35, vorhandene Gradle-Abhängigkeiten:
`gradle --offline --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
Das Laufartefakt bindet Commit, Quellarchiv, XML-Testergebnisse, Lint, APK-Hash
und Signatur. Eine lokale Prüfung ist kein GitHub-CI-Nachweis.
