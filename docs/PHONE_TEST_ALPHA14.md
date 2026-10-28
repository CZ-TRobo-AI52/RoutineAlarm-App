# Alpha14 auf Christians Gerät prüfen

Nur die APK aus dem GitHub-Testrelease `v0.2.0-alpha14-test` verwenden. Erwartet: `com.cztr.routinealarm.dev`, `0.2.0-alpha14-test`, VersionCode 16. Die Metadaten und das gleiche Zertifikat wie Alpha12 ermöglichen ein Update; die tatsächliche Installation auf dem Gerät bleibt zu prüfen.

Alpha12 behalten und Alpha14 darüber installieren. Bei einem Installationsfehler abbrechen und den Wortlaut melden; Alpha12 nicht deinstallieren, Daten nicht löschen und keine Wiederherstellung erzwingen. Die gesonderte App „RoutineAlarm Handytest“ ist ein anderes Paket und kein Update dieses Releases.

Für jeden Fall Zeit, beobachtetes Verhalten und PASS/FAIL/NOT_AVAILABLE eintragen. Fehlende neue Funktionen als NOT_AVAILABLE dokumentieren, keine versteckte Aktivierung versuchen.

| case_id | Erwartung | Beobachtet | Status | Zeit / Notizen |
|---|---|---|---|---|
| INSTALL-UPDATE | Update über Alpha12 ohne Datenverlust; Alpha14/16 angezeigt | | | |
| START | Start ohne Datenbank-/Migrationsfehler | | | |
| SYSTEM | System-/Testansicht öffnet; Berechtigungen nachvollziehbar | | | |
| ALARM-1MIN | Testalarm erstellen, nach einer Minute klingeln, TTS, Benachrichtigung, Alarmseite prüfen | | | |
| DONE | Erledigen beendet den Testalarm; kein erneuter Effekt beim zweiten Tippen | | | |
| SKIP | Überspringen dort prüfen, wo angeboten; sonst NOT_AVAILABLE | | | |
| SNOOZE-1 | Auswahl 1 Minute; nur ein Folgealarm | | | |
| SNOOZE-2 | Auswahl 2 Minuten; nur ein Folgealarm | | | |
| SNOOZE-3 | Auswahl 3 Minuten; nur ein Folgealarm | | | |
| SNOOZE-4 | Auswahl 4 Minuten; nur ein Folgealarm | | | |
| SNOOZE-5 | Auswahl 5 Minuten; nur ein Folgealarm | | | |
| NOTIFICATION-OPEN | Benachrichtigung öffnet passenden Alarm | | | |
| NOTIFICATION-ACTION | Neuer NotificationActionAdapter-Consumer fehlt: NOT_AVAILABLE | | | |
| DUPLICATE | Schnelles Doppel-Tippen erzeugt keinen zweiten sichtbaren Folgealarm | | | |
| LOCKED | Alarm mit geschlossener App und gesperrtem Bildschirm beobachten | | | |
| SCREEN-CLOSE | Schließen der Ansicht ist kein DONE; Zustand danach kontrollieren | | | |
| OUTPUT-CLOSE | Dauerhafter CLOSE_OUTPUT/CloseAck fehlt: NOT_AVAILABLE; nicht als DONE werten | | | |
| OUTPUT-ISOLATION | Veraltete Ansicht darf keinen neu gestarteten fremden Output beenden | | | |
| RESTART-APP | App schließen/aus Recents entfernen; geplanten Testalarm prüfen | | | |
| RESTART-DEVICE | Nach Geräteneustart geplanten Alarm und Berechtigungen prüfen | | | |
| RESTORE | Nur sichtbare, sichere Restore-Prüfung mit separater Zustimmung; Quarantäne darf nicht selbst aktivieren | | | |

Gerät, Android-Version, genaue APK-SHA256 sowie installierte Vorgängerversion mit dem Ergebnis melden. Ein bestandener kurzer Versuch belegt keine langfristige OEM-/Doze-/Akkuzuverlässigkeit.
