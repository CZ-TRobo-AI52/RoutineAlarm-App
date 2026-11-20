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

## R52 Skill Intelligence Core — Integrationsslice

Ein neuer side-effect-freier Kotlin-Core bildet die erste reale R52-RCEA-Integration: Skill-Lifecycle und Provenance, monotone Registry, deterministisches Routing mit Negative-Trigger-Veto, Capability-Komposition, evidenzgebundene Promotion, Anti-Goodhart-Utility und ein rekursiver Improvement-Kernel mit ROI- und Stop-Regel. `R52SkillEngine` stellt diese Funktionen über eine schmale Fassade bereit.

Dieser Slice verändert weder Alarmzustellung noch Occurrence-/Recovery-/Notification-/Training-Pfade und führt keinen zweiten Store ein. Der lokale pure-Kotlin Contract-Harness besteht mit 11/11 Checks. Der Repository-JUnit-Vertrag ist Bestandteil des Patches; vollständige Gradle-/Lint-/APK-CI ist erst nach Anwendung auf dem Alpha14-Branch aussagekräftig. Deshalb sind BENCHMARKED, DEPLOYED und VERIFIED ausdrücklich noch nicht erreicht.

Details: [R52 Skill Intelligence Core](R52_SKILL_INTELLIGENCE.md).

## R52 Skill-Admission-Sicherheitskorrektur C002 (Patch, nicht im Release)

Aufbauend auf dem R52 Skill Intelligence Core ersetzt C002 selbstdeklarierte Skill-Freigaben durch einen fail-closed Admission-Vertrag: Registrierung akzeptiert ausschließlich `PROPOSED`, höhere Versionen benötigen eine exakte Parent-Bindung, und Lifecycle-Fortschritt ist nur mit einer am SHA-256-Skillfingerprint gebundenen, durch einen host-kontrollierten Evidence-Verifier bestätigten Receipt möglich. Replay derselben Receipt ist idempotent; kollidierende IDs, falsche Stufen, falsche Typen, Regressionen und Verifier-Fehler werden verweigert. Verifizierte Utility kann aus bloßen Selbstbericht-Zählern nicht erzeugt werden. Nichtendliche ROI-Eingaben sind ungültig.

Dies ist **kein** fertiger produktiver Evidenzdienst: Die Standard-Authority verweigert alle Receipts, und die positiven Tests verwenden ausdrücklich einen simulierten Verifier. Authentische Forge-/Signer-/Ground-Truth-Evidenz, persistentes Audit, Wiederanlauf und ein tatsächlicher App-Consumer müssen später nachweisbar angebunden werden. Kein Alpha14-APK- oder UI-Funktionsversprechen entsteht aus diesem Patch. Lokale Kotlin-Tests sind nicht gleichbedeutend mit Gradle CI oder Handytest.

Details: [R52 Skill Intelligence](R52_SKILL_INTELLIGENCE.md) und [C002 Admission Report](R52_SKILL_ADMISSION_C002.md).

## R52 Skill Intelligence C003 — signed evidence integration candidate

A pure-Kotlin Ed25519 evidence authority and 15 additional local contract tests
are provided as an integration candidate. They extend the uncommitted C002
skill core; this is not a second Android engine, a production issuer, a new
persistent store, or a released Alpha14 feature. The verifier binds host-pinned
issuer identity, audience, source commit, receipt content, evidence SHA-256 and expiry, and
separates independent holdout attestation from ordinary CI receipts. See
[R52 Skill Signed Evidence C003](R52_SKILL_SIGNED_EVIDENCE_C003.md).

The candidate requires the existing Forge host's independently verified signer,
canonical storage adapter and end-to-end Android CI before any DEPLOYED or
VERIFIED label. No change has yet been committed to the GitHub remote branch.

## R52 Skill Evolution C005 — VERIFIED-Admission-Härtung (unveröffentlichter Patch)

C005 schließt die in C004 dokumentierte Lücke: Eine gültig signierte aber semantisch beliebige Ground-Truth-Datei darf die Registry nicht mehr in `VERIFIED` versetzen. Der Default verweigert diesen Übergang. Eine explizit vom Host festgelegte `PinnedHoldoutVerifiedAdmission` validiert denselben signierten Holdout-Vertrag wie der C004-Selector einschließlich Benchmark-Bindung, Stichprobenuntergrenze und konservativer positiver Konfidenzuntergrenze. Ein gemeinsamer Proof-Verifier verhindert abweichende Prüfpfade. **Keine Änderung der Android-Weckerfunktion.** Nur lokale Kotlin-Tests; kein Remote-Commit, Gradle-CI, Forge-Holdout oder Deploy nachgewiesen. Details: [C005 VERIFIED Admission](R52_SKILL_VERIFIED_ADMISSION_C005.md).
