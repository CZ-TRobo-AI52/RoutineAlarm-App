# Alpha07: veraltete Testalarme aufräumen

Testversion `0.2.0-alpha07-test`, Versionscode 9, Paket `com.cztr.routinealarm.dev`.

Nach einer Verschiebung oder Erledigung kann ein bereits begonnener Android-Aufruf noch die alte Alarmkennung registrieren. Alpha07 gleicht bekannte überholte Kennungen beim Wiederherstellungseinstieg und nach einer gespeicherten Benutzerentscheidung ab. Gültige zukünftige Tests werden weiterhin erneut eingeplant. Der gespeicherte Verlauf bleibt unverändert.

## Auf dem Handy prüfen

1. Alpha07 über die bisherige, gleich signierte Testinstallation installieren. Verlauf, Sicherungen und vorhandene Testwecker müssen erhalten bleiben. Schema 1/2/3 wird transaktional auf 4 erweitert; gegenüber Alpha06 kommt nur ein Suchindex hinzu. Epoche und Importpause bleiben erhalten.
2. Einen Testwecker auslösen und **5 Minuten später** wählen. App schließen und wieder öffnen. Nur die aktuelle Verschiebung soll wirken. Anschließend erledigen beziehungsweise überspringen und erneut öffnen.
3. Einen überfälligen wartenden Test im Verlauf ausdrücklich mit **Erneut planen** starten. Diese manuelle Wiederholung bleibt erlaubt.
4. Einen zukünftigen Test behalten und das Gerät neu starten. Wochenplan zunächst pausiert lassen; der Testpfad muss unabhängig davon wiederhergestellt werden.
5. Die bestehenden [Telefonprüfungen](PHONE_TEST.md) und [Sicherungsprüfungen](ALPHA06.md) fortsetzen. Importierte offene Tests bleiben bis zur ausdrücklichen Aktivierung pausiert.

Gerätemodell, Androidversion, Build, Uhrzeit und tatsächliche Beobachtung festhalten. **Kein Realgerätetest wurde für diese Version durchgeführt.** Hosttests simulieren Android-Verhalten; sie bestätigen keine echte Zustellung, Audioausgabe oder Akkulaufzeit.

## Technische Grenzen

Der Abgleich kennt die in der eigenen Datenbank gespeicherten Planungsversuche. Er besitzt keine vollständige Android-Alarminventur und kann die ursprünglichen Geräte-Epochen importierter Versuche nicht rekonstruieren. Fehlende Rückmeldungen bleiben unbekannte Wirkungen. Ein zurückgekehrter Cancel-Aufruf wird nicht als Zustellbeweis ausgegeben.

Die Verarbeitung erfolgt in Seiten von 64 Identitäten. Der Gesamtaufwand und die Historiengröße sind weiter Gegenstand der offenen M2.2b-Arbeit. Wochenplan und Training sind noch nicht in den gemeinsamen Occurrence-Pfad migriert.

Ältere APKs können Schema 4 nicht öffnen. Ein APK-Downgrade ist kein Daten-Rollback. Das portable Sicherungsformat bleibt unverändert; ein Import benötigt weiterhin einen leeren kompatiblen Store.

Quellcommit, Tests, Lint, Hostmessungen, frischer Archiv-Build, APK-Prüfsumme und CI werden im Lieferbericht festgehalten. [Details zum Abgleich](M2.2b-stale-alarm-reconciliation.md).
