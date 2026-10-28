# RoutineAlarm Alpha14 Testrelease

Package: `com.cztr.routinealarm.dev`; Version: `0.2.0-alpha14-test`; VersionCode: `16`.

## Enthalten

- Bestehendes Occurrence-Journal mit stabiler Action-Identität, unveränderlichem ursprünglichem Payload, prepare/submit/recover und Konfliktprüfung bei wiederverwendetem Request-Key.
- DONE, SKIP und Snooze 1–5 im getesteten Core-Vertrag; vorhandene Testalarm-Bedienung bleibt bestehen.
- Gemeinsame Notification-Adapter-/Core-Vertragstests und stabile UI-Request-Keys.
- Trennung von Screen Close, Output Close und Occurrence-Aktion; lokale Output-Instanz-Fences, verspätete TTS-Callbacks und Shutdown-Races sind abgesichert.
- Restore-/Epoch-/Scope-/Quarantäne-, Receiver- und Service-Authority-Prüfungen aus der bestehenden Alpha13-Linie.

## Bewusste Grenzen dieses Testrelease

Die neue NotificationActionAdapter-Schicht ist integriert und hostgetestet, wird aber noch nicht von einem realen Produktconsumer aufgerufen. TestAlarmActivity verwendet den vorhandenen Coordinator-Pfad. Eine Notification-Schnellaktion über den neuen stabilen Vertrag wird deshalb nicht als verfügbare Funktion beworben.

Der neue CLOSE_OUTPUT-Vertrag antwortet fail-closed mit OutputCloseUnavailable. Ein dauerhafter tokenbezogener CloseRequest/Attempt/Ack-/Recovery-Pfad fehlt. Lokaler Output-Stop besitzt Instanzschutz, ist aber kein persistenter CloseAck. Historische Aktionsergebnisse erteilen keine erneute Effektberechtigung.

Die persönliche persistente Ausführung, gemeinsame Output-Arbitration und der vollständige ChronOS-/Voice-/UI-Blueprint sind nicht fertig. Keine neue Architektur oder Produktfunktion wurde während Release Closure ergänzt.

## Verifikation

Das Release-Gate führt vollständige lokale und frische Quellbuilds sowie CI auf genau dem Releasecommit aus. Die vier Alpha14-Metadatentests lesen das tatsächlich zusammengeführte Android-Paket. Alle 604 bisherigen eindeutigen Testfälle müssen erhalten bleiben; zusätzliche Versionsfälle werden separat ausgewiesen.

Die Release-Artefakte binden Commit, Tree, Source-ZIP, APK, Testberichte, Original-Testzertifikat und Remote-Hashes. CI-Debug-APK und final mit dem bisherigen Testsigner signierte APK sind unterschiedliche Artefakte. Testrelease ist keine Produktions- oder Geräteabnahme.

Der Geräteprüfstatus lautet bis zu einem tatsächlichen Ergebnis für dieses APK: NOT_YET_TESTED_ON_CHRISTIANS_DEVICE. OEM-/Doze-, Akku- und langfristige Zuverlässigkeit bleiben ungeprüft.
