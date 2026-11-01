package com.cztr.routinealarm.ui

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.backup.BackupActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Release entry-point reentrancy, before a picker result or repository I/O exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33])
class C4ReleaseSystemBackupBoundaryTest {
    @Test fun rapidExportDoubleTapOpensOnlyOneDocumentPicker() = withBackup { activity ->
        val save = button(activity, "Datei speichern")
        save.performClick()
        // Reuse the actual original button: a queued second tap must not start another chooser.
        save.performClick()
        val opened = mutableListOf<Intent>()
        while (true) opened += shadowOf(activity).nextStartedActivity ?: break
        assertTrue(opened.all { it.action == Intent.ACTION_CREATE_DOCUMENT })
        assertEquals("Two rapid save taps opened multiple Android document pickers", 1, opened.size)
    }

    @Test fun rapidImportDoubleTapShowsOnlyOneConfirmation() = withBackup { activity ->
        val load = button(activity, "Datei laden")
        try {
            load.performClick()
            load.performClick()
            val confirmations = ShadowDialog.getShownDialogs().filterIsInstance<AlertDialog>()
                .filter { it.isShowing }
            assertEquals("Two rapid load taps left multiple restore confirmations active", 1, confirmations.size)
            assertNull("No document selection before explicit confirmation", shadowOf(activity).nextStartedActivity)
        } finally { ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() } }
    }

    @Test fun missingDocumentProviderStaysNonfatalAndKeepsSecurityWarning() = withBackup { activity ->
        val app = RuntimeEnvironment.getApplication()
        val request = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
        assertNull(activity.packageManager.resolveActivity(request, 0))
        shadowOf(app).checkActivities(true)
        try {
            repeat(2) {
                button(activity, "Datei speichern").performClick()
                val labels = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.contains("Kein Dokumentanbieter verfügbar. Es wurden keine Daten geändert."))
                assertTrue(labels.any { it.contains("unverschlüsselt") })
                assertFalse(activity.isFinishing)
                assertTrue(button(activity, "Datei speichern").isEnabled)
            }
        } finally { shadowOf(app).checkActivities(false) }
    }

    private fun withBackup(check: (BackupActivity) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val db = app.getDatabasePath("occurrences.db")
        assertFalse("Read-only empty-store fixture must not open a database", db.exists())
        val controller = Robolectric.buildActivity(BackupActivity::class.java).create()
        try { check(controller.get()) }
        finally {
            controller.destroy()
            assertFalse("Picker/confirmation UI must not create or mutate the occurrence store", db.exists())
        }
    }
    private fun button(activity: BackupActivity, label: String) = views(activity.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == label }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
