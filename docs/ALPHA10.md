# Alpha10: Trainingssitzungen zuverlässig beenden

Testversion `0.2.0-alpha10-test`, Versionscode 12, Paket `com.cztr.routinealarm.dev`.

## Änderungen

- Eine Sitzung ohne Trainingsansagen beendet sich sofort, auch während die Sprachausgabe noch initialisiert wird.
- Eine verspätete erfolgreiche Sprachinitialisierung kann eine abgebrochene Sitzung nicht erneut starten.
- Jede Sitzung besitzt eine eigene Kennung. Der Abschluss einer alten Ansage kann keine neue Sitzung beenden, auch wenn beide denselben Trainingstag betreffen.
- Ein Sitzungswechsel beendet die vorherige Sprachausgabe und entfernt deren geplante Ansagen.

Die gemeinsamen Prüfungen für Occurrence-Zustellungen werden aus dem bestehenden Testalarm-Coordinator ausgegliedert und dort weiterverwendet. Ihr expliziter Geltungsbereich erhält die Trennung zwischen Testalarmen und persönlichen Routinen. Epochen, Generationen, Revisionen und die Restore-Sperre bleiben wirksam. Die Wochenroutine wird in dieser Version noch nicht auf einen neuen Ausgabepfad umgestellt.

## Handyprüfung

1. Alpha10 über die bisherige Test-App installieren. Vorhandener Verlauf und Wochenplan-Einstellung sollen erhalten bleiben.
2. Ein-Minuten-Testalarm bei gesperrtem Display, Erledigen und Verschieben erneut prüfen.
3. Bei einer regulären Trainingssitzung mehrere deutsche Ansagen beobachten. Über **Training beenden** abbrechen; spätere Ansagen und die laufende Benachrichtigung sollen ausbleiben.
4. App schließen und neu öffnen. Keine bereits beendete Trainingssitzung soll erneut sprechen.
5. Version, Gerätemodell, Android-Version und Beobachtungen festhalten.

Für Alpha10 liegen noch keine Realgerätetests vor. Der Nutzerbericht zu Seeker / Android 16 gilt für Alpha09. Robolectric prüft Dienstlogik und Handler mit kontrollierten TTS-Rückmeldungen; hörbare Ausgabe und tatsächliche Android-Zustellung benötigen den Handytest.

## Grenzen und nächste Schritte

Schema 4 und das Backupformat bleiben erhalten. Ein Prozessabbruch setzt Training weiterhin nicht automatisch an einem gespeicherten Cue fort. Wochenalarm-Receipts, Wiederherstellung, Ablösung alter Planstände und eine UI für persönliche Occurrences sind vor der schrittweisen Weekly-Migration zu ergänzen. Der vorhandene Testalarm-Adapter akzeptiert weiterhin ausschließlich Testalarme.
