# R52 Skill Intelligence Core

## Ziel

Dieser Slice integriert den deterministischen Kern der R52 Recursive Capability Evolution Architecture in die RoutineAlarm-Alpha14-Quelllinie, ohne bestehende Alarm-, Occurrence-, Recovery-, Notification- oder Training-Semantik zu verändern.

Der Kern ist absichtlich pure Kotlin. Dadurch kann er unabhängig vom Android-Lifecycle geprüft, versioniert und später über explizite Ports an Persistenz, UI, Engram/Memory, Agenten und Remote-Evals angeschlossen werden.

## Integrierte Bausteine

- `SkillLifecycleState`: PROPOSED → IMPLEMENTED → TESTED → BENCHMARKED → DEPLOYED → VERIFIED.
- `SkillVersion` und `SkillProvenance`: monotone Versionen, Parent-Version, Hypothese, Eval-IDs und Änderungsgrund.
- `SkillRegistry`: idempotente Registrierung, Schutz gegen stale Versionen und gleichversionige Konflikte.
- `SkillRouter`: deterministisches Trigger-/Capability-Routing mit Negative-Trigger-Veto.
- `SkillComposition`: greedy Capability-Coverage mit begrenzter Skill-Anzahl und ohne Duplikate.
- `SkillEvidence` und `SkillPromotionGate`: rein beratende, nicht authentisierte Zähler-Vorprüfung; tatsächliche Zustandsänderungen sind ausschließlich mit `SkillRegistry.promote` und einer host-authentisierten Receipt möglich.
- `SkillEvalResult`: `estimatedUtility()` ist ein unbestätigtes Planungssignal; `validatedUtility()` bleibt null, bis ein unabhängiger Outcome-Verifier tatsächlich angebunden ist.
- `RecursiveImprovementKernel`: Auswahl nach validiertem ROI statt Rohgewinn; Rekursion nur wenn Meta-Gewinn Kosten + Risiko + Complexity Debt strikt übersteigt.
- `R52SkillEngine`: schmale Fassade für Registry, Routing, Composition und Promotion-Gates.

## Invarianten

1. Ein älterer Skill darf einen neueren niemals ersetzen.
2. Gleiche Version + anderer Inhalt ist ein Konflikt und kein Update.
3. Negative Trigger dürfen positive Trigger explizit vetoen.
4. VERIFIED ist ohne authentifizierte, inhaltlich gebundene Ground-Truth-Receipt verboten.
5. Regressionen blockieren jede Promotion.
6. Unvalidierte Improvement-Kandidaten gewinnen keine ROI-Auswahl.
7. Meta-Rekursion stoppt bei nicht-positivem marginalem Netto-Nutzen.
8. Der Core besitzt in diesem Slice keine Android- oder SQLite-Seiteneffekte.

## Status dieses Slices

| Stufe | Status | Evidenz |
| --- | --- | --- |
| PROPOSED | erfüllt | R52-RCEA Spezifikation + Implementierungsplan |
| IMPLEMENTED | erfüllt im lokal konsolidierten C002-Patch | C001-Kern plus SkillAdmission, Receipt-Gate, Test- und Dokumentationsdelta |
| TESTED | lokal erfüllt; Repository-CI ausstehend | C001-Smoke 11/11, C002-Admisson 9/9, JUnit-kompatibler lokaler Lauf 20/20; echte Gradle/JUnit- und Android-Tests noch ausstehend |
| BENCHMARKED | offen | noch kein Baseline-vs-Candidate Routing-/Capability-Benchmark im Repository |
| DEPLOYED | offen | keine Alpha14-APK mit diesem Patch erzeugt |
| VERIFIED | offen | keine Geräte-/externen Ground-Truth-Evidenzen |

## Nächste Integrationsstufe

1. Patch auf `codex/routinealarm-alpha14-integration` anwenden.
2. GitHub Actions vollständig ausführen: Unit-Tests, Lint, Debug-APK.
3. Bei Green den Core als TESTED im Repository einstufen.
4. Persistenzport auf Basis eines einzigen autoritativen Stores entwerfen; keinen zweiten Store einführen.
5. Einen realen Alpha14-Consumer anbinden, zunächst read-only/routing-only.
6. Baseline-vs-Candidate-Evals für Routing Precision/Recall, Task Success, Token-/Latenzkosten und Regressionen ausführen.
7. Erst nach Benchmark, Deployment-Receipt und externer Ground Truth höhere Lifecycle-Stufen zulassen.

## C002 — fail-closed Authentizitätsgrenze

Der initiale Core enthielt drei nachweisbare Vertrauenslücken: Ein Descriptor konnte bei `register` sofort `VERIFIED` behaupten; ungebundene `SkillEvidence`-Zähler konnten `VERIFIED` und scheinbar bestätigte Utility liefern; NaN/Infinity konnte in ROI-Entscheidungen gelangen. C002 beseitigt die direkten Bypass-Pfade:

- Registrierung akzeptiert nur `PROPOSED`; ein Versionsupgrade benötigt `provenance.parentVersion == current.version` und startet erneut bei `PROPOSED`.
- `SkillFingerprint.of()` bindet SHA-256 an ID, Version, Zweck, Trigger, Fähigkeiten, Dependencies und Provenienz — nicht an den veränderlichen Laufzeitzustand.
- Ein zustandsverändernder Übergang erfolgt nur mit `SkillRegistry.promote(id, stage, SkillReceipt)`; die Receipt muss genau zu ID, Version, Fingerprint, nächster Stufe und Evidenztyp passen. Regressionen und Receipt-ID-Kollisionen blockieren; exakte Wiederholung ist idempotent.
- Der Host muss eine `SkillEvidenceAuthority` zur Authentisierung beisteuern. Standardmäßig ist diese **deny-all**. Test-Fixtures mit `authenticate() = true` sind keine externen Nachweise und **dürfen nicht** in Produktcode verdrahtet werden.
- Das ältere `SkillPromotionGate.evaluate()` mit ungeprüften Zählern ist nur eine beratende Vorprüfung; bei `VERIFIED` verweigert es immer. `R52SkillEngine.canPromote()` gibt für Selbstberichts-Zähler keinen Freigabebescheid aus.
- `SkillEvalResult.estimatedUtility()` bleibt ein Planungssignal. `validatedUtility()` liefert ohne angebundenen unabhängigen Outcome-Verifier **0**. Dies verhindert falsche belegte Fähigkeitszuwächse.
- Alle numerischen Verbesserungseingaben müssen endlich sein. Der ROI verwendet ein Basiskostenintervall statt ein nahezu nullwertiges Ersatzintervall, das Nullkosten-Behauptungen extrem aufwertet.

### Betriebsgrenzen

Aktueller Nachweis ist eine lokale Kotlin/JVM-Ausführung mit simulierten positiven Test-Authorities. Es liegt **kein authentischer externer Witness und keine reale Forge-Sink-Akzeptanz** vor. `SkillRegistry`/Receipt-History sind im Speicher; bei Prozessverlust wird nicht behauptet, bereits gespeicherter Zustand könne automatisch wiederhergestellt werden. Ohne echte Authority bleibt der Skill in `PROPOSED` und ist bei Standardrouting unsichtbar. Das ist der beabsichtigte sichere Ausgangszustand. Für die Integration muss der Host Verifier, authentische Belegbindung, Persistenz und Replay-Schutz liefern, ohne einen zweiten konkurrierenden Store zu eröffnen.

C002 ist erst als **lokaler Patchkandidat IMPLEMENTED und TESTED_LOCAL** eingestuft. Es gibt keine Repository-Gradle-CI, Android-Build-, Deployment- oder extern verifizierte Nutzenevidenz. Der unveränderte Alpha14-Basisbranch ist **nicht** automatisch dieses Patchrelease.
