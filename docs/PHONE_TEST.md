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

Die vorhandene Alarmoberfläche, Wochenroutine und Trainingsansagen sowie den
neuen getesteten M2.1-Datenkern. Die bestehenden Alarmaktionen sind noch nicht
an dessen Journal angebunden. Es gibt weiterhin den bisherigen 5-Minuten-Button;
die fünf getrennten +1 bis +5 Buttons folgen mit der M2.2/M2.3-Integration.
Core-Verbindung, Sensorfunktionen und eine produktive Gerätefreigabe stehen aus.

Die APK ist eine Testversion. Ein erfolgreicher Build ersetzt keinen Handytest.
Die bestehende Release-App muss dafür weder deinstalliert noch zurückgesetzt werden.
Spätere Updates dieser Test-App müssen mit demselben Debug-Schlüssel signiert sein;
lokale und GitHub-Actions-Builds können unterschiedliche Debug-Schlüssel verwenden.
