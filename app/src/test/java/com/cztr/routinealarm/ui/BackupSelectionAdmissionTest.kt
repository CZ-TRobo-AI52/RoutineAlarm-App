package com.cztr.routinealarm.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.backup.BackupActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33])
class BackupSelectionAdmissionTest {
    @Test fun cancellationAllowsFreshExportWithoutStoreAccess() = withBackup { a ->
        button(a, "Datei speichern").performClick()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(a).nextStartedActivity.action)
        assertFalse(button(a, "Datei laden").isEnabled)
        result(a, 401, Activity.RESULT_CANCELED, null)
        assertTrue(button(a, "Datei speichern").isEnabled)
        button(a, "Datei speichern").performClick()
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(a).nextStartedActivity.action)
    }

    @Test fun wrongResultDoesNotReleaseExistingPickerAdmission() = withBackup { a ->
        val staleSave = button(a, "Datei speichern")
        staleSave.performClick()
        assertNotNull(shadowOf(a).nextStartedActivity)
        result(a, 402, Activity.RESULT_CANCELED, null)
        staleSave.performClick()
        assertNull(shadowOf(a).nextStartedActivity)
        assertFalse(button(a, "Datei speichern").isEnabled)
        result(a, 401, Activity.RESULT_CANCELED, null)
        assertTrue(button(a, "Datei speichern").isEnabled)
    }

    @Test fun unsolicitedAndAlreadyConsumedResultsDoNotAccessStore() = withBackup { a ->
        val document = Intent().setData(Uri.parse("content://test/no-authority"))
        result(a, 401, Activity.RESULT_OK, document)
        button(a, "Datei speichern").performClick()
        assertNotNull(shadowOf(a).nextStartedActivity)
        result(a, 401, Activity.RESULT_CANCELED, null)
        result(a, 401, Activity.RESULT_OK, document)
        awaitWorker(a)
        assertTrue(button(a, "Datei speichern").isEnabled)
    }

    @Test fun nullAndInvalidUriResultsReleaseAdmissionWithoutIo() = withBackup { a ->
        for (data in listOf<Intent?>(null, Intent(), Intent().setData(Uri.parse("file:///not-allowed")))) {
            button(a, "Datei speichern").performClick()
            assertNotNull(shadowOf(a).nextStartedActivity)
            result(a, 401, Activity.RESULT_OK, data)
            assertTrue(button(a, "Datei speichern").isEnabled)
            assertTrue(button(a, "Datei laden").isEnabled)
        }
        awaitWorker(a)
    }

    @Test fun confirmationBlocksCrossActionAndOldPositiveCallback() = withBackup { a ->
        val staleSave = button(a, "Datei speichern")
        button(a, "Datei laden").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        staleSave.performClick()
        assertNull(shadowOf(a).nextStartedActivity)
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(a).nextStartedActivity.action)
        positive.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(a).nextStartedActivity)
        assertFalse(button(a, "Datei laden").isEnabled)
    }

    @Test fun savedPickerAdmissionSurvivesRecreationWithoutRelaunch() {
        val first = Robolectric.buildActivity(BackupActivity::class.java).create()
        val state = Bundle()
        button(first.get(), "Datei speichern").performClick()
        assertNotNull(shadowOf(first.get()).nextStartedActivity)
        first.saveInstanceState(state).destroy()
        val second = Robolectric.buildActivity(BackupActivity::class.java).create(state)
        try {
            assertNull(shadowOf(second.get()).nextStartedActivity)
            assertFalse(button(second.get(), "Datei speichern").isEnabled)
            result(second.get(), 401, Activity.RESULT_CANCELED, null)
            assertTrue(button(second.get(), "Datei speichern").isEnabled)
            assertNoStore()
        } finally { second.destroy() }
    }

    @Test fun destroyedConfirmationHasNoLatePickerAuthority() {
        val controller = Robolectric.buildActivity(BackupActivity::class.java).create()
        val a = controller.get()
        button(a, "Datei laden").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        controller.destroy()
        assertFalse(dialog.isShowing)
        positive.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(a).nextStartedActivity)
        assertNoStore()
    }

    private fun result(a: BackupActivity, request: Int, status: Int, data: Intent?) {
        BackupActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(a, request, status, data)
    }
    private fun awaitWorker(a: BackupActivity) {
        val field = BackupActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        (field.get(a) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun assertNoStore() = assertFalse(RuntimeEnvironment.getApplication()
        .getDatabasePath("occurrences.db").exists())
    private fun withBackup(check: (BackupActivity) -> Unit) {
        assertNoStore()
        val controller = Robolectric.buildActivity(BackupActivity::class.java).create()
        try { check(controller.get()); assertNoStore() }
        finally { controller.destroy(); ShadowDialog.getShownDialogs().toList().forEach { it.dismiss() } }
    }
    private fun button(a: BackupActivity, label: String) = views(a.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == label }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
}
