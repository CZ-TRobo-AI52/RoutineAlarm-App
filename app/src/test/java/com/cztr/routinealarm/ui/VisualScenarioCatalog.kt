package com.cztr.routinealarm.ui

enum class VisualSurface {
    HOME,
    PLAN,
    STUDIO,
    HISTORY,
    SYSTEM,
    ALARM,
    BACKUP,
    TEST_ALARM,
}

enum class VisualTheme { LIGHT, DARK }

data class VisualViewport(
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
)

data class VisualScenario(
    val id: String,
    val surface: VisualSurface,
    val theme: VisualTheme,
    val state: String,
    val viewport: VisualViewport,
) {
    val snapshotName: String = buildString {
        append(id)
        append('_')
        append(theme.name.lowercase())
        append('_')
        append(viewport.widthDp)
        append('x')
        append(viewport.heightDp)
        append('_')
        append((viewport.fontScale * 100).toInt())
    }
}

/**
 * Bounded pairwise visual coverage. It deliberately avoids a full Cartesian product while
 * covering every primary surface in both themes and at 320dp with 200% font at least once.
 */
object VisualScenarioCatalog {
    private fun scenario(
        id: String,
        surface: VisualSurface,
        theme: VisualTheme,
        state: String,
        widthDp: Int,
        heightDp: Int = 800,
        fontScale: Float = 1.0f,
    ) = VisualScenario(id, surface, theme, state, VisualViewport(widthDp, heightDp, fontScale))

    val scenarios: List<VisualScenario> = listOf(
        scenario("home_active", VisualSurface.HOME, VisualTheme.LIGHT, "home_active", 360),
        scenario("home_paused_large", VisualSurface.HOME, VisualTheme.DARK, "home_paused", 320, 720, 2.0f),
        scenario("home_empty", VisualSurface.HOME, VisualTheme.LIGHT, "home_empty", 411, 891, 1.3f),
        scenario("home_active_tablet", VisualSurface.HOME, VisualTheme.DARK, "home_active", 600, 960),

        scenario("plan_active", VisualSurface.PLAN, VisualTheme.LIGHT, "plan_active", 360),
        scenario("plan_permission_large", VisualSurface.PLAN, VisualTheme.DARK, "plan_permission_required", 320, 720, 2.0f),
        scenario("plan_paused", VisualSurface.PLAN, VisualTheme.DARK, "plan_paused", 411, 891, 1.3f),
        scenario("plan_training_detail", VisualSurface.PLAN, VisualTheme.LIGHT, "plan_training_detail", 600, 960),

        scenario("studio_read_only", VisualSurface.STUDIO, VisualTheme.LIGHT, "studio_read_only", 360),
        scenario("studio_filter_large", VisualSurface.STUDIO, VisualTheme.DARK, "studio_filter_paused", 320, 720, 2.0f),
        scenario("studio_empty", VisualSurface.STUDIO, VisualTheme.DARK, "studio_empty", 411, 891, 1.3f),
        scenario("studio_detail", VisualSurface.STUDIO, VisualTheme.LIGHT, "studio_detail", 600, 960),

        scenario("history_available", VisualSurface.HISTORY, VisualTheme.LIGHT, "history_available", 360),
        scenario("history_unavailable_large", VisualSurface.HISTORY, VisualTheme.DARK, "history_unavailable", 320, 720, 2.0f),
        scenario("history_triggered", VisualSurface.HISTORY, VisualTheme.DARK, "history_triggered_not_delivery", 411, 891, 1.3f),
        scenario("history_loading", VisualSurface.HISTORY, VisualTheme.LIGHT, "history_loading", 600, 960),

        scenario("system_ready", VisualSurface.SYSTEM, VisualTheme.LIGHT, "system_ready", 360),
        scenario("system_action_large", VisualSurface.SYSTEM, VisualTheme.DARK, "system_action_required", 320, 720, 2.0f),
        scenario("system_recovery_unknown", VisualSurface.SYSTEM, VisualTheme.DARK, "system_recovery_unknown", 411, 891, 1.3f),
        scenario("system_theme", VisualSurface.SYSTEM, VisualTheme.LIGHT, "system_theme_selected", 600, 960),

        scenario("alarm_default", VisualSurface.ALARM, VisualTheme.DARK, "alarm_default", 360),
        scenario("alarm_pending_large", VisualSurface.ALARM, VisualTheme.DARK, "alarm_pending_snooze_retry", 320, 720, 2.0f),
        scenario("alarm_light", VisualSurface.ALARM, VisualTheme.LIGHT, "alarm_default", 411, 891, 1.3f),
        scenario("alarm_close_boundary", VisualSurface.ALARM, VisualTheme.LIGHT, "alarm_close_boundary", 600, 960),

        scenario("backup_idle", VisualSurface.BACKUP, VisualTheme.LIGHT, "backup_idle", 360),
        scenario("backup_busy_large", VisualSurface.BACKUP, VisualTheme.DARK, "backup_busy", 320, 720, 2.0f),
        scenario("backup_error", VisualSurface.BACKUP, VisualTheme.DARK, "backup_error", 411, 891, 1.3f),
        scenario("backup_restore_boundary", VisualSurface.BACKUP, VisualTheme.LIGHT, "backup_restore_boundary", 600, 960),

        scenario("test_alarm_active", VisualSurface.TEST_ALARM, VisualTheme.LIGHT, "test_alarm_active", 360),
        scenario("test_alarm_invalid_large", VisualSurface.TEST_ALARM, VisualTheme.DARK, "test_alarm_invalid", 320, 720, 2.0f),
        scenario("test_history_paused", VisualSurface.TEST_ALARM, VisualTheme.DARK, "test_history_paused_restore", 411, 891, 1.3f),
        scenario("test_history_unavailable", VisualSurface.TEST_ALARM, VisualTheme.LIGHT, "test_history_unavailable", 600, 960),
    )
}
