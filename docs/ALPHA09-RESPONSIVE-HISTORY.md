# Alpha09: Teststatus und Verlauf im Hintergrund laden

Testversion `0.2.0-alpha09-test`, Versionscode 11, Paket `com.cztr.routinealarm.dev`.
Ausgangspunkt ist Alpha08, Commit `d55dbd55f02ad1d62a18fe25a8183000513d9ae0`.

## Änderung

Die Startkarte für Testwecker und die Testalarm-Ansicht öffnen und lesen ihre Occurrence-Datenbank auf einem eigenen Hintergrund-Worker. Der Hauptthread zeigt zunächst einen Ladezustand und bleibt für Navigation verfügbar. Jede Ansicht akzeptiert nur das Ergebnis ihrer neuesten Anfrage. Ein inzwischen veraltetes oder nach `onStop` eintreffendes Ergebnis ersetzt die aktuelle Ansicht nicht. Beim Schließen darf ein bereits laufender Lesezugriff seine Ressourcen geordnet freigeben; sein Ergebnis wird verworfen.

Die Startkarte zeigt erst nach dem Laden den nächsten Test und die Zähler für offene, ausgelöste und überfällige Testwecker. Ein Lesefehler wird als nicht verfügbarer Status mit erneutem Ladeversuch angezeigt. Er erscheint nicht als leere Datenbank.

Im Testverlauf steht zunächst „Testverlauf wird geladen …“. Bei einem Alarm wird zunächst seine Identität geprüft. Die zugehörigen Aktionen erscheinen erst nach erfolgreichem Lesen. „Schließen“ bleibt währenddessen verfügbar. Ein Fehler bietet „Erneut versuchen“. Ein neuer Alarm-Intent oder eine neue Verlaufseite macht ältere Ergebnisse ungültig. Ladezustand und Aktionsverfügbarkeit gehören damit immer zur aktuellen Anfrage.

## Begrenzte und konsistente Lesezugriffe

- `statusSummary(now)` berechnet die Zähler in SQL und dekodiert höchstens **einen** Occurrence-Snapshot für den nächsten zukünftigen Test. Pausierte importierte Ereignisse zählen nicht zum aktiven Status. Als überfällig gelten geplante oder verschobene Tests mit aktueller Weckzeit bis einschließlich `now`.
- `historyPage(offset)` wählt zuerst höchstens **20** Testereignisse aus. Erst danach werden deren letzte Planungsversuche und gegebenenfalls Rückmeldungen verbunden. Ein Versuch ohne Rückmeldung bleibt sichtbar und hat weiterhin einen unbekannten Ausgang.
- Die Historie ist nach geplantem Zeitpunkt und Ereignis-ID absteigend sortiert. „Ältere Tests“ und „Neuere Tests“ wechseln in Schritten von 20 Einträgen. Eine vollständig gefüllte letzte Seite kann noch einen Wechsel auf eine leere Folgeseite anbieten.
- Zähler und nächster Test beziehungsweise Verlaufseite, Importpause und Zustellungsepoche werden jeweils aus einem gemeinsamen SQL-Snapshot gelesen. Zwischen verschiedenen Ladevorgängen können neue Ereignisse hinzukommen.
- Lesende Darstellung verändert weder Ereignisstatus noch Execution Journal, Planungsbelege oder Importfreigaben. Die bestehende Hintergrund-Recovery wird weiterhin gesondert angefordert.

Diese Grenzen betreffen die zurückgegebenen und dekodierten Ereignisse. SQL-Aggregationen, Sortierung und das Überspringen früherer Seiten können mit der Datenbank wachsen. Es gibt keine zugesicherte Millisekunden-Latenz und noch keine neue Aufbewahrungsregel. Bei gleichzeitig veränderter Historie sind Seiten keine dauerhaft eingefrorene Gesamtliste.

## Unveränderter Umfang

Schema 4 und das portable Backupformat bleiben unverändert. Vorhandene Ereignisse, gespeicherte Entscheidungen, Importpausen und die Recovery aus Alpha08 bleiben erhalten.

Erledigen, Verschieben, Überspringen, erneutes Planen, Aktivieren und Erstellen bleiben im bisherigen synchronen Ablauf. Dieser serialisiert die Entscheidungen mit der Service-Ausgabe auf dem Hauptthread. Ihre Verlagerung benötigt einen eigenen Schritt mit Tests gegen konkurrierende Alarmausgabe; ein bloßer zusätzlicher Executor würde diese Reihenfolge nicht bewahren.

Wochenplan- und Trainingsmigration, die vollständige Auslagerung aller UI-Arbeit, Retention und die kanonische Robo-AI52-Anbindung sind nicht Teil dieses Schritts. Ein angezeigter Planungsbeleg beweist weiterhin keine tatsächliche Alarmzustellung.

## Automatisierte Prüfung

Im Quellcode sind 21 neue Regressionstests enthalten:

| Bereich | Neue Tests | Schwerpunkt |
|---|---:|---|
| SQL-Lesemodelle | 8 | Statusfilter, Snooze-Zeit, exakte Fälligkeitsgrenze, Tageswechsel, Importpause, stabile Seiten, letzter Versuch ohne Rückmeldung, keine Mutation durch Lesen |
| `LatestUiLoader` | 5 | Hintergrundausführung, Callback-Ausgabe, neueste Anfrage, Stop/Destroy, Fehler und anschließender neuer Versuch |
| Asynchrone Ansichten | 8 | blockierter DB-Zugriff ohne blockierte Navigation, neuer Alarm während laufender Anfrage, Stop/Destroy und sichtbare Lesefehler |

Die lokale vollständige Suite ist bestanden: **182 Tests in 23 Suites**, keine Fehler, Fehlschläge oder übersprungenen Tests. Die JUnit-XML-Dateien bestätigen diese Zahlen. Android Lint meldet 0 Fehler und 14 Warnungen; die Debug-APK wurde gebaut. Der gemeinsame Lauf dauerte 1 Minute 46 Sekunden und führte 22 von 51 Tasks aus; 29 waren bereits aktuell.

Frischer Quellarchiv-Build, Signaturprüfung, Prüfsummen, CI und Veröffentlichung werden separat nach ihrem tatsächlichen Abschluss im Liefernachweis dokumentiert. Ein Realgerätetest ist weiterhin offen.

Die Hosttests nutzen echte App-Ansichten und Datenbankzugriffe mit gezielt blockierten oder fehlschlagenden Reads. Robolectric bildet Plattformgrenzen ab. Daraus folgt kein Nachweis für reales Geräte-, Audio-, Energie- oder OEM-Verhalten.

## Handyprüfung

1. **Upgrade:** Alpha09 über eine gleich signierte Alpha08-Testinstallation installieren. Paket und Versionscode kontrollieren. Ereignisse, Snooze-Zustände, gespeicherte Entscheidungen und Importpausen müssen erhalten bleiben. Backup vorher exportieren; ein APK-Downgrade wurde nicht als Daten-Rollback geprüft.
2. **Startkarte:** Mit leerem und mit gefülltem Testverlauf öffnen. Während des Ladens zwischen den vorhandenen Bereichen navigieren. Danach Zähler und nächsten Test mit dem Verlauf vergleichen. Ein Import mit pausierten Testereignissen darf diese noch nicht als aktive Tests zählen.
3. **Seiten:** Bei mehr als 20 Testereignissen ältere und neuere Seiten öffnen. Pro Seite höchstens 20 Einträge, Datumssortierung und letzte Planungsrückmeldungen prüfen. Anschließend zur Startansicht zurückkehren.
4. **Navigation während des Ladens:** Verlauf wiederholt öffnen und schließen oder während einer Anfrage einen tatsächlichen Testalarm öffnen. Es darf nachträglich kein älterer Verlauf an die Stelle des aktuellen Alarms treten. Erledigen und Verschieben weiterhin praktisch gegen die hörbare Ausgabe prüfen.
5. **Hintergrund und Rückkehr:** Während eine Ansicht lädt zur Android-Startseite wechseln, danach zurückkehren. Auch Drehen beziehungsweise erneutes Öffnen prüfen. Ein gestoppter oder geschlossener Bildschirm darf keine verspätete Anzeige über den neuen Bildschirm legen. Bei schnellen Geräten kann der Ladezustand zu kurz für eine Beobachtung sein; die erzwungene Verzögerung ist dann nur durch den Hosttest belegt.
6. **Fehler und Wiederholung:** Falls auf einem Testgerät ein Lesefehler tatsächlich auftritt, muss eine Fehlermeldung statt falscher Nullwerte beziehungsweise leerer Historie erscheinen. „Erneut versuchen“ prüfen. Ohne kontrollierte Fehlerumgebung diesen Punkt als nicht durchgeführt markieren; die private Datenbank nicht zur Fehlererzeugung beschädigen. Der automatisierte Test injiziert den Fehler gezielt.
7. **Bestehendes Alarmverhalten:** Alarmausgabe, exakte Alarmberechtigung, Boot-Recovery, manuelles erneutes Planen und Importaktivierung nach [Alpha08](ALPHA08.md) und der [allgemeinen Handyprüfung](PHONE_TEST.md) testen.

Gerätemodell, Androidversion, vorherige Appversion, Uhrzeit und tatsächliche Beobachtung festhalten. Für Alpha09 wurde noch kein Realgerätetest durchgeführt. Ladeanzeige und flüssige Navigation ersetzen keine gemessene Zustellungszuverlässigkeit.
