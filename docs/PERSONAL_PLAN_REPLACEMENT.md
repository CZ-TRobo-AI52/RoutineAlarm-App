# Persönliche Planablösung – Entscheidungsvertrag

Dieser Schritt ergänzt ausschließlich `PersonalPlanReplacement`: eine deterministische Vorbereitung für den vorhandenen SQLite-Store. Die App ruft diesen Vertrag noch nicht auf. Datenbankschema 4, Backups, Android-Ausführung und APK-Version bleiben unverändert.

## Vertrag

- Ein Aufruf betrifft genau eine persönliche Event-ID. Der vollständige gespeicherte Bestand dieser Event-ID und deren Autorität müssen aus demselben Datenbank-Snapshot kommen.
- Version 0 enthält keinen früheren Plan und keine Ablösungen. Eine benutzte Autorität braucht ihre Eventbindung und aktuelle Planrevision. Jede abgelöste Identität muss in der vollständigen Historie dieses Events vorkommen; ein unvollständiger Snapshot wird abgewiesen.
- `expectedVersion` schützt gegen konkurrierende Änderungen. `Conflict` enthält keine anwendbare Änderung. `Ready` enthält die gemeinsam anzuwendende nächste Autorität, neue Einträge, permanente Ablösungen und erhaltene Identitäten.
- Eine Planrevision bindet ein vollständiges Fenster mit höchstens 366 Einträgen sowie Titel, Sprache, Kategorie, Modus und Kalenderidentitäten. Ein neues Fenster oder geänderter Inhalt braucht eine neue Revision. Die Reihenfolge und reine Beobachtungszeit beeinflussen die Bindung nicht.
- Innerhalb eines Plans gibt es höchstens eine Ausführung je nominalem Kalenderdatum. Unterschiedliche Uhrzeiten oder die beiden Offsets einer Zeitumstellung berechtigen nicht zu einer zweiten Ausführung derselben Tagesroutine.
- Eine erneute Auswertung der aktuellen Revision mit identischem Inhalt und aktueller `expectedVersion` erzeugt keine Änderung. Eine Wiederholung mit alter `expectedVersion` liefert einen CAS-Konflikt; Transport-Idempotenz ist damit noch nicht bewiesen. Eine bereits abgelöste Revision oder Identität darf nicht erneut aktiv werden, auch nicht nach einem leeren Plan.
- Der Zeitschnitt ist ein ausdrücklich übergebener UTC-Instant. Nur `SCHEDULED` mit initialen Zählern und geplantem Zeitpunkt **nach** diesem Schnitt darf abgelöst werden. Fällige Einträge einschließlich exakter Gleichheit sowie `TRIGGERED`, `SNOOZED` und terminale Historie bleiben erhalten.
- Neue bereits fällige Einträge werden abgewiesen. Eine neue Identität darf keine erhaltene Ausführung desselben nominalen Datums überlagern. Dies ist eine konservative Sperre, bis eine explizite Konfliktregel für Änderungen laufender Tage besteht.
- Das Ergebnis besitzt eigene unveränderliche Collections. Es erzeugt keine Plattformwirkung, keine Ausführungsaktion und keine Zustellberechtigung.
- `preservedIds` enthält nur unverändert erhaltene, nicht abgelöste Identitäten und ist immer disjunkt zu den kumulativen Ablösungen. Schon abgelöste historische Zeilen werden ebenfalls nicht gelöscht. Fällige und laufende Vorgänge dürfen beim späteren Anschluss nicht allein aufgrund einer älteren Kopf-Revision ausgeblendet werden. Auch `preservedIds` allein hebt keine Restore-Quarantäne auf.

## Noch erforderlich vor Aktivierung

1. Autorität und permanente Ablösungen mit atomarem CAS in den vorhandenen Store integrieren; vollständige Event-Historie in derselben Transaktion lesen.
2. Schema-Migration und versionierten Backup-Vertrag ergänzen. Ablösungen müssen nach Restore erhalten bleiben; Restore-Aktivierung allein darf sie nicht aufheben.
3. Scheduling, bounded SQL-Abfragen, Zustellung und transaktionale Aktionen gegen aktuelle Autorität prüfen. Bestehende Action-Replays behalten ihre ursprüngliche Entscheidung.
4. Neue Recovery-Zyklen nach Planwechsel und monotone Token-Bereinigung prüfen.
5. Erst anschließend persönliche Android-Tokens und erreichbare explizite Restore-Aktivierung anschließen.

Die pure Versionsentscheidung beweist keinen Datenbank-Doppelstartschutz. Transaktions-, Absturz-, Migrations- und Realgerätetests gehören zu den anschließenden Integrationsschritten.
