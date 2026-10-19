# Alpha05: nachvollziehbare Android-Planungsversuche

Die lokale Testversion `0.2.0-alpha05-test` (Code 7, Paket `com.cztr.routinealarm.dev`) ergänzt alpha04 um gespeicherte Planungsversuche und Rückmeldungen. Eine fehlende Rückmeldung nach Prozessabbruch bleibt ausdrücklich unbekannt. Wiederherstellung kann den aktuellen zukünftigen Test mit derselben PendingIntent-Identität erneut anmelden. Details: [Datenmodell, Migration und Grenzen](M2.2b-scheduling-receipts.md).

## Abnahme auf dem Handy

Die bisherigen [Telefonprüfungen](PHONE_TEST.md) bleiben erforderlich. Zusätzlich:

1. Unter alpha04 Testverlauf und bestehende Einträge festhalten. Alpha05 mit demselben Testschlüssel installieren. Alte Einträge und Entscheidungen müssen erhalten bleiben.
2. Einen zukünftigen Test anlegen und den Verlauf öffnen. Die Planungsrückmeldung darf nur den zurückgekehrten Android-Aufruf bestätigen. Sie darf keine tatsächliche Ausgabe behaupten.
3. Genaue Alarme entziehen und die App erneut öffnen. Der wartende Test bleibt erhalten; der neue Versuch muss die fehlende Berechtigung nennen. Nach Wiedererteilung vor der Weckzeit zur App zurückkehren und die tatsächliche Ausgabe prüfen.
4. Mehrfach vor der Weckzeit zur App zurückkehren, dann einmalige Zustellung, Snooze und anschließende Erledigung prüfen. Ein früherer Zustand darf den erledigten Test nicht reaktivieren.
5. Gerät rechtzeitig vor einem verschobenen Test neu starten. Wiederherstellung und hörbare/sichtbare Ausgabe separat beobachten.

Gerätemodell, Androidversion, Build, Zeit und tatsächliches Ergebnis dokumentieren. Es wurden keine Realgerätetests durchgeführt. Erzwungene Prozessabbrüche, Doze und Herstellerbeschränkungen sind damit noch nicht praktisch abgenommen.

## Daten erhalten

Schema 1 wird additiv auf Schema 2 migriert. Ein installierter alter Build versteht die neue Datenbank nicht; ein Downgrade ist keine Wiederherstellungsstrategie. Für Vergleiche einen separaten geeigneten Testdatenstand verwenden. Automatischer Export und vollständige Wiederherstellung bleiben offen.

## Verifizierungsstatus

Der Lieferbericht enthält den endgültigen Commit, Test-/Lint-Ergebnisse, APK-Hash und Status der Veröffentlichung. Lokaler Build, GitHub-CI, Veröffentlichung und Geräteabnahme werden getrennt dokumentiert. Die Überführung von Wochenplan und Training in das Journal ist nicht Bestandteil dieser Version.
