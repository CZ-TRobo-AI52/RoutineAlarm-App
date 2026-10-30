package com.cztr.routinealarm.ui.system

/** Pure presentation mapping. It does not grant permission, schedule alarms or confirm effects. */
object SystemUiMapper {
    fun map(input: SystemUiInput): SystemUiState {
        val notifications = when {
            !input.notificationPermissionRequired -> SystemStatus(
                title = "Benachrichtigungen",
                detail = "Diese Android-Version verlangt keine separate Laufzeitfreigabe. Eine sichtbare oder hörbare Zustellung ist damit nicht bewiesen.",
                readiness = SystemReadiness.READY,
            )
            input.notificationGranted -> SystemStatus(
                title = "Benachrichtigungen",
                detail = "Freigabe erteilt. Tatsächliche Zustellung bleibt ein eigener beobachtbarer Effekt.",
                readiness = SystemReadiness.READY,
            )
            else -> SystemStatus(
                title = "Benachrichtigungen",
                detail = "Freigabe fehlt. Erinnerungen können dadurch unsichtbar bleiben.",
                readiness = SystemReadiness.ACTION_REQUIRED,
                action = SystemAction.REQUEST_NOTIFICATIONS,
                actionLabel = "Benachrichtigungen erlauben",
            )
        }
        val exactAlarms = if (input.exactAlarmGranted) SystemStatus(
            title = "Exakte Wecker",
            detail = "Android erlaubt exakte Planung. Ein erfolgreicher Planungsaufruf beweist noch keine spätere Ausgabe.",
            readiness = SystemReadiness.READY,
        ) else SystemStatus(
            title = "Exakte Wecker",
            detail = "Freigabe fehlt. Zeitkritische Routinen und Testwecker können nicht exakt geplant werden.",
            readiness = SystemReadiness.ACTION_REQUIRED,
            action = SystemAction.REQUEST_EXACT_ALARMS,
            actionLabel = "Exakte Wecker erlauben",
        )
        val weeklyPlan = if (input.weeklyPlanEnabled) SystemStatus(
            title = "Wochenplan",
            detail = "Aktiviert. Planstatus ist keine Bestätigung, dass jede Ausgabe tatsächlich zugestellt wurde.",
            readiness = SystemReadiness.READY,
        ) else SystemStatus(
            title = "Wochenplan",
            detail = "Pausiert. Die Plan-Oberfläche kann ihn nach den bestehenden Berechtigungsprüfungen aktivieren.",
            readiness = SystemReadiness.PAUSED,
        )
        val recovery = SystemStatus(
            title = "Recovery",
            detail = if (input.recoveryRequested) {
                "Wiederanlauf wurde für diese Sitzung angefordert; Abschluss und externe Wirkungen sind damit nicht bestätigt."
            } else {
                "Für diese Darstellung ist noch keine aktuelle Recovery-Anforderung bestätigt."
            },
            readiness = SystemReadiness.UNKNOWN,
            action = SystemAction.OPEN_HISTORY,
            actionLabel = "Verlauf prüfen",
        )
        val backup = SystemStatus(
            title = "Sicherung & Wiederherstellung",
            detail = "Manueller Export und Import sind verfügbar. Dateien sind unverschlüsselt; importierte persönliche Ereignisse bleiben in diesem Stand gesperrt.",
            readiness = SystemReadiness.UNKNOWN,
            action = SystemAction.OPEN_BACKUP,
            actionLabel = "Ereignisverlauf sichern / laden",
        )
        return SystemUiState(
            notifications = notifications,
            exactAlarms = exactAlarms,
            weeklyPlan = weeklyPlan,
            recovery = recovery,
            backup = backup,
            theme = input.theme,
            versionName = input.versionName,
            packageName = input.packageName,
            buildClaim = "Testbuild. Host-, CI-, Geräte- und Langzeitnachweise werden getrennt geführt.",
        )
    }
}
