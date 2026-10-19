# RoutineAlarm Test: erster Handytest

Diese vorläufige Debug-App heißt **RoutineAlarm Test** und verwendet
`com.cztr.routinealarm.dev`. Sie lässt sich neben der bisherigen Release-App
installieren. Android 8.0 oder neuer ist erforderlich.

## Installation und kurzer Test

1. Die bereitgestellte APK aufs Handy kopieren und öffnen. Falls Android fragt,
   die Installation für den verwendeten Dateimanager erlauben.
2. **RoutineAlarm Test** öffnen. Den Wochenplan zunächst ausgeschaltet lassen,
   damit die Test-App keine zusätzlichen Wochenalarme neben der bisherigen App plant.
3. Unter **System & Test** Benachrichtigungen und exakte Wecker erlauben.
4. **TESTWECKER IN 1 MINUTE** drücken, das Display sperren und auf Alarm und
   deutsche Sprachansage warten.
5. **Erledigt** prüfen. Anschließend einen neuen Testwecker starten und
   **5 Minuten später** prüfen. Im dritten Durchlauf **Überspringen** prüfen.
6. Tages-/Trainingsübersicht öffnen, App schließen und erneut starten.

Bitte Gerätemodell, Android-Version, APK-Version und Beobachtungen festhalten:
Alarm sichtbar? Sprache hörbar? Sperrbildschirm? Verschieben nach fünf Minuten?
Wenn kein Vollbild erscheint, auch die Benachrichtigung prüfen und dies berichten.

## Was dieser Stand enthält

Seit alpha03 hat der Ein-Minuten-Testwecker einen eigenen persistenten Verlauf:
Erledigt, Überspringen und Verschieben bleiben gespeichert. Der Testverlauf ist
unter **System & Test** erreichbar. Wochenroutine und Trainingsansagen verwenden
weiterhin ihren bisherigen Ausgabepfad. Es gibt den 5-Minuten-Button; die fünf
getrennten +1 bis +5 Buttons folgen mit der weiteren Integration.
Core-Verbindung, Sensorfunktionen und eine produktive Gerätefreigabe stehen aus.

Seit [alpha04](ALPHA04.md) werden zukünftige Testwecker bei App-Rückkehr und
passenden Systemereignissen wiederhergestellt. Die Startansicht zeigt ihren
gespeicherten Status und öffnet den Verlauf direkt. Den erweiterten Gerätecheck
beschreibt [M2.2b](M2.2b-test-recovery.md). Das veröffentlichte alpha03-Paket
enthält diese Fortsetzung noch nicht; seinen manuellen Wiederaufnahmeweg
beschreibt der [M2.2a-Testbericht](M2.2-test-journal.md).

Die APK ist eine Testversion. Ein erfolgreicher Build ersetzt keinen Handytest.
Seit [alpha08](ALPHA08.md) erfolgt automatische Testwecker-Recovery im Hintergrund
mit gespeichertem Fortschritt. Android bestimmt den Startzeitpunkt dieser Arbeit.
Seit [alpha06](ALPHA06.md) kann der Testverlauf als Datei gesichert und in eine
leere Testinstallation übernommen werden. Übernommene offene Tests bleiben bis
zur einzelnen Bestätigung pausiert. Die Datei ist unverschlüsselt; Wochenplan-
Einstellungen sind nicht enthalten. Den Restore bitte auf einer separaten leeren
Testinstallation prüfen, ohne den vorhandenen Verlauf zurückzusetzen.

Die bestehende Release-App muss dafür weder deinstalliert noch zurückgesetzt werden.
Spätere Updates dieser Test-App müssen mit demselben Debug-Schlüssel signiert sein;
lokale und GitHub-Actions-Builds können unterschiedliche Debug-Schlüssel verwenden.
