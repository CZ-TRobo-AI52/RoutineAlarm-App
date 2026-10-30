package com.cztr.routinealarm.ui

import org.junit.Assert.*
import org.junit.Test

class VisualScenarioCatalogTest {
    @Test fun scenarioIdsAndSnapshotNamesAreUniqueAndStable() {
        val scenarios = VisualScenarioCatalog.scenarios
        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size)
        assertEquals(scenarios.size, scenarios.map { it.snapshotName }.toSet().size)
        assertTrue(scenarios.all { it.snapshotName.matches(Regex("[a-z0-9_]+")) })
    }

    @Test fun everyPrimarySurfaceCoversLightAndDark() {
        VisualSurface.entries.forEach { surface ->
            val themes = VisualScenarioCatalog.scenarios.filter { it.surface == surface }.map { it.theme }.toSet()
            assertTrue("Light missing for $surface", VisualTheme.LIGHT in themes)
            assertTrue("Dark missing for $surface", VisualTheme.DARK in themes)
        }
    }

    @Test fun everyPrimarySurfaceHasSmallWidthLargeFontCoverage() {
        VisualSurface.entries.forEach { surface ->
            assertTrue("320dp / 200% missing for $surface",
                VisualScenarioCatalog.scenarios.any {
                    it.surface == surface && it.viewport.widthDp == 320 && it.viewport.fontScale == 2.0f
                })
        }
    }

    @Test fun criticalTruthAndRecoveryStatesAreCovered() {
        val states = VisualScenarioCatalog.scenarios.map { it.state }.toSet()
        listOf(
            "home_paused", "home_active", "plan_permission_required", "studio_read_only",
            "history_unavailable", "history_triggered_not_delivery", "system_action_required",
            "alarm_pending_snooze_retry", "backup_busy", "test_alarm_invalid", "test_history_paused_restore",
        ).forEach { assertTrue("Missing visual state $it", it in states) }
    }

    @Test fun catalogRemainsBoundedInsteadOfCartesianExplosion() {
        assertTrue(VisualScenarioCatalog.scenarios.size in 24..64)
        assertTrue(VisualScenarioCatalog.scenarios.all { it.viewport.heightDp >= 568 })
    }
}
