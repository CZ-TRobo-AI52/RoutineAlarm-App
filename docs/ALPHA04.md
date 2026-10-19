# Alpha04: Wiederherstellung und sichtbarer Testwecker-Status

Stand: 17.10.2026. Entwicklungsstand auf `codex/routinealarm-m2-occurrences`.

## Nutzen dieses Schritts

Die Hauptansicht zeigt gespeicherte offene Testwecker, überfällige Einträge und den nächsten zukünftigen Test. Der Testverlauf ist direkt erreichbar. Eine fehlende Erlaubnis für genaue Alarme bekommt einen direkten Weg zu den Systemeinstellungen. Diese Angaben beschreiben gespeicherte Zustände; sie garantieren keine Zustellung und bestätigen keine real ausgeführte Tätigkeit.

Zukünftige Testwecker werden bei App-Rückkehr und den vorhandenen System-Broadcasts aus der SQLite-Projektion wieder eingeplant, auch bei pausiertem Wochenplan. Überfällige oder bereits ausgelöste Tests benötigen eine bewusste Wiederaufnahme. Details und Grenzen stehen in [M2.2b](M2.2b-test-recovery.md).

Beim Wechsel vom Alarmbildschirm zum Verlauf werden die Einstellungen für Sperrbildschirm und Bildschirmwachhalten zurückgesetzt, auch wenn das Speichern einer Verschiebung erfolgreich war, die Android-Planung aber scheitert.

## Testversion

- Paket: `com.cztr.routinealarm.dev`, Version `0.2.0-alpha04-test`, Code 6.
- Android ab 8.0 / API 26, Debug-Signatur. Bestehende Release-App bleibt getrennt.
- Aktualisierung der lokalen alpha03-Testversion mit demselben Debug-Schlüssel vorgesehen. Keine Datenbankmigration.
- Alpha03 bleibt als veröffentlichter Rückvergleich verfügbar. Ein Downgrade über eine neuere Version wird nicht als Android-Standardinstallation vorausgesetzt; vor Gerätevergleichen Daten erhalten.
- Am 17.10.2026 bestanden 67 Tests (darunter neun Recovery-, fünf Fenster-, sieben Darstellungs- und drei Navigationstests). Vor dem Fenster-Fix schlugen drei der fünf Fenster-Tests reproduzierbar fehl.
- Vollständiger Test-/Lint-/APK-Lauf: erfolgreich in 1 Minute 3 Sekunden; Lint: 0 Fehler, 12 bestehende Warnungen. APK-Signatur v2 geprüft. Kein Realgerätetest durchgeführt.
- APK-SHA256: `a7d73c5bb0679333ebf7a0dc7ecb6ac240ab46b154616bdc66fd64ef9c3e22eb`. Commit, CI und Veröffentlichungsstatus stehen im Lieferbericht; daraus folgt noch keine vollständige M2.2b-Abnahme.

## Handyabnahme

1. Wochenplan pausieren, Ein-Minuten-Test anlegen. Startkarte muss den kommenden Test zeigen. Testverlauf mit einer Aktion öffnen.
2. Klingelnden Test um fünf Minuten verschieben, Gerät vor Ablauf neu starten. Auf tatsächliche Ausgabe und einmalige Zustandsänderung achten.
3. Genaue Alarme entziehen. Die Startkarte muss den konkreten Bedarf nennen. Wieder erlauben und vor der Weckzeit zur App zurückkehren.
4. Einen Termin verstreichen lassen, ohne ihn auszulösen; App öffnen. Überfälligen Zustand prüfen, kein automatisches erneutes Klingeln erwarten. Bewusst im Verlauf erneut planen.
5. Alarm öffnen und zum Verlauf wechseln. Verlauf soll den Bildschirm nicht weiter wachhalten oder über dem Sperrbildschirm erscheinen.
6. Erledigte Tests dürfen nach App-/Geräteneustart nicht wieder klingeln. Wochenplan und Training separat auf Regressionen prüfen.

Gerät, Androidversion, Build, Aktion, erwartete und beobachtete Zeit sowie sichtbare/hörbare Ausgabe festhalten. Force-Stop, Doze und Herstellerbeschränkungen sind auf einem echten Gerät zu testen.

## Produktziel und nächste Schritte

Die Arbeitshypothese ist eine Routine-App, mit der Menschen Unterbrechungen schnell und nachvollziehbar bewältigen. Der neue Testpfad ist ein technischer Zwischenschritt. Persönliche Routinen sind noch nicht vollständig auf das Journal umgestellt; der Plan ist weiterhin im Quellcode vorgegeben. KI-Funktionen, Marktvorsprung und Umsatz werden mit dieser Version nicht behauptet.

Als Nächstes: persönliche Routinen editierbar machen und transaktional anbinden, gemeinsame Ausgabe mit Training, Export/Restore, danach kontrollierte KI-Vorschläge auf freigegebenem Kontext. Der Alarmkern soll ohne Netz und ohne KI verfügbar bleiben. Nutzen zunächst mit Gerätezuverlässigkeit, Bedienzeit und wiederkehrender Nutzung messen.
