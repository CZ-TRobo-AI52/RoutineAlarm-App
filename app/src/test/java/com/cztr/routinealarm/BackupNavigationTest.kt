package com.cztr.routinealarm

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import com.cztr.routinealarm.backup.BackupActivity
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupNavigationTest {
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun dashboardOpensUserControlledBackupScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            awaitUi(activity.get())
            // The user-approved five-destination shell groups system tools under More.
            val nav = activity.get().window.decorView.let { root ->
                fun find(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                    (0 until view.childCount).flatMap { find(view.getChildAt(it)) } else emptyList()
                find(root).filterIsInstance<com.cztr.routinealarm.ui.components.R52BottomNav>().single()
            }
            buttons(nav).single { it.text == "Mehr" }.performClick()
            assertNull("Navigation alone must not start backup", shadowOf(activity.get()).nextStartedActivity)
            val button = buttons(activity.get().window.decorView)
                .single { it.text == "Ereignisverlauf sichern / laden" }
            button.performClick()
            assertEquals("com.cztr.routinealarm.backup.BackupActivity",
                shadowOf(activity.get()).nextStartedActivity.component!!.className)
        } finally { activity.destroy(); awaitUiReadersStopped(activity.get()) }
    }

    @Test fun exportUsesDocumentPickerWithoutBroadStorageAccess() {
        val activity = Robolectric.buildActivity(BackupActivity::class.java).create()
        try {
            buttons(activity.get().window.decorView).single { it.text == "Datei speichern" }.performClick()
            val intent = shadowOf(activity.get()).nextStartedActivity
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertEquals("application/octet-stream", intent.type)
            assertNull(intent.data)
        } finally { activity.destroy() }
    }

    @Test fun importCancellationDoesNotOpenAFileAndConfirmationUsesPicker() {
        val activity = Robolectric.buildActivity(BackupActivity::class.java).create()
        try {
            fun choose() = buttons(activity.get().window.decorView).single { it.text == "Datei laden" }.performClick()
            choose()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(activity.get()).nextStartedActivity)
            choose()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(activity.get()).nextStartedActivity.action)
        } finally { activity.destroy() }
    }
}
