# Alpha06: Testverlauf sichern und kontrolliert wiederherstellen

Testversion `0.2.0-alpha06-test`, Versionscode 8, Paket `com.cztr.routinealarm.dev`.

Unter **System & Test → Testverlauf sichern / laden** lässt sich der Testverlauf in eine selbst gewählte Datei sichern. Die Wiederherstellung prüft die Datei und übernimmt sie nur in einen leeren Testverlauf. Offene Tests bleiben pausiert, bis sie einzeln im Verlauf bestätigt werden. Alte Alarmkennungen sind nach einer Wiederherstellung ungültig. Der Verlauf hat jetzt Seiten für mehr als 20 Tests.

## Auf dem Handy prüfen

1. Alpha06 über die vorherige lokal signierte Test-APK installieren. Bestehende Einträge müssen erhalten bleiben. Die normale Release-App verwendet ein separates Paket.
2. Einen Testwecker anlegen, auslösen, verschieben und erledigen. Zusätzlich einen zukünftigen Test behalten. Den Verlauf über den Android-Dateidialog sichern.
3. Dieselbe Datei bei vorhandenem Verlauf laden: Die App muss den Import ablehnen; alle vorhandenen Einträge bleiben erhalten.
4. Für den eigentlichen Restore eine **separate leere Testinstallation auf einem zweiten Gerät oder in einem separaten Android-Nutzerprofil** verwenden. Die bestehende Installation nicht löschen oder zurücksetzen.
5. Die Datei laden, App schließen/öffnen und Gerät neu starten. Übernommene offene Tests bleiben pausiert; sie dürfen allein dadurch weder sprechen noch klingeln.
6. Im Verlauf einen übernommenen Test aktivieren. Die Bestätigung weist darauf hin, dass vergangene Zeiten sofort auslösen können. Abbrechen darf keine Aktivierung bewirken. Nach Bestätigung tatsächliche Benachrichtigung, Sprache und Erledigung prüfen.
7. Bei mehr als 20 Einträgen über **Ältere Tests / Neuere Tests** blättern und einen älteren übernommenen Test aktivieren.

Gerätemodell, Androidversion, Build, Uhrzeit und tatsächliche Beobachtung festhalten. Die bisherigen [Telefonprüfungen](PHONE_TEST.md) bleiben erforderlich. Hier wurden keine Realgerätetests durchgeführt.

## Grenzen

- Die Datei ist **unverschlüsselt** und kann persönliche Titel und Sprachtexte enthalten. Nur einen privaten Speicherort und eigene Sicherungen verwenden.
- Gesichert werden der Occurrence-Store, sein Journal und historische Planungsrückmeldungen. Wochenplan-Einstellungen und tatsächliche Android-Alarmregistrierungen sind nicht enthalten.
- Maximal 8 MiB, 50.000 Datensätze insgesamt und 256 KiB je Textfeld. Größere oder ungültige Dateien werden abgelehnt. Eine Prüfsumme bestätigt Integrität, keine Herkunft oder Alarmausgabe.
- Datenbankschema 1/2 wird additiv auf 3 migriert. Ältere APKs können Schema 3 nicht öffnen. Ein Downgrade ist kein Restore.
- Abweichende zukünftige Zeitzonenregeln können historische Planungen bei der strikten Prüfung zurückweisen. Bestehende Zieldaten werden dabei nicht überschrieben.
- Nach einem Prozessabbruch unmittelbar nach dem Commit kann der Import bereits gespeichert sein, obwohl keine Bestätigung angezeigt wurde. Den Verlauf prüfen; ein erneuter Import überschreibt ihn nicht.

## Nachweise

116 Tests bestanden nach der unabhängigen Reviewkorrektur; anschließend bestand zusätzlich die Messung mit 1.000 Ereignissen. Auf diesem Windows-/Robolectric-Host: 1.475.001 Byte Sicherung, Export rund 710 ms, Restore rund 448 ms. Das ist eine einzelne Hostmessung, kein Handy-, Akku- oder Vergleichsbenchmark. Lint: keine Fehler, 14 Warnungen (12 bestehende und zwei zusätzliche Hinweise auf nicht ausgelagerte UI-Texte).

Der Lieferbericht nennt den endgültigen Commit, den vollständigen frischen Archiv-Build, APK-Prüfsumme, CI und Veröffentlichungsstatus. [Technische Beschreibung](M2.2b-backup-restore.md). Die vollständige Migration von Wochenplan und Training bleibt offen.
