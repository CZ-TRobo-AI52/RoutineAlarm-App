package com.cztr.routinealarm.ui

import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.backup.BackupActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.SqliteOccurrenceRepository
import com.cztr.routinealarm.occurrence.TestAlarmCoordinator
import com.cztr.routinealarm.ui.backup.*
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.shell.ThemeChoice
import com.cztr.routinealarm.ui.shell.ThemePreferenceStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Real native text measurement and SQLite/UI boundaries; not a physical-device result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31], qualifiers = "w280dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupNativeLayoutBoundaryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC)
    private val received = mutableListOf<BackupUiAction>()

    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(2f)
        context.deleteDatabase("occurrences.db")
        context.deleteDatabase("backup-layout-source.db")
    }

    @After fun cleanup() {
        RuntimeEnvironment.setFontScale(1f)
        context.deleteDatabase("occurrences.db")
        context.deleteDatabase("backup-layout-source.db")
    }

    @Test fun darkContentAndActionsRemainFullyReadable() = verifyContent(R52Colors.Dark)
    @Test fun lightContentAndActionsRemainFullyReadable() = verifyContent(R52Colors.Light)

    @Test fun standardFontRetainsReadableContentAndTypedActions() {
        RuntimeEnvironment.setFontScale(1f)
        verifyContent(R52Colors.Dark)
    }

    @Test fun darkLongFailureAndFileNameRemainComplete() = verifyLongFailure(R52Colors.Dark)
    @Test fun lightLongFailureAndFileNameRemainComplete() = verifyLongFailure(R52Colors.Light)

    @Test fun busyExportAndImportKeepActionsDisabledInDark() = verifyBusy(R52Colors.Dark)
    @Test fun busyExportAndImportKeepActionsDisabledInLight() = verifyBusy(R52Colors.Light)

    @Test fun quarantineWarningRemainsExplicitDuringExportAndImport() {
        for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
            for (operation in listOf(BackupOperation.EXPORTING, BackupOperation.IMPORTING)) {
                val screen = screen(BackupUiInput(busy = true, operation = operation), colors)
                layout(screen)
                val warning = views(screen).filterIsInstance<TextView>().singleOrNull {
                    it.text.contains("pausiert") && it.text.contains("Testwecker")
                }
                assertNotNull("Import quarantine must remain explicit while the status shows $operation", warning)
                assertReadable(requireNotNull(warning))
                reveal(screen, warning)
            }
        }
        assertTrue(received.isEmpty())
    }

    @Test fun renderingAndScrollingDoNotRestoreOrActivateQuarantinedRows() {
        val id = seedQuarantinedRow()
        TestAlarmRuntime.repository(context).use { repo ->
            val bytes = repo.exportBackup()
            val epoch = repo.deliveryEpoch()
            val history = repo.history(id)
            val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.toList()
            for (colors in listOf(R52Colors.Dark, R52Colors.Light)) {
                val screen = screen(BackupUiInput(), colors)
                layout(screen)
                views(screen).filterIsInstance<TextView>().forEach { reveal(screen, it) }
                layout(screen)
            }
            assertTrue("Layout must not emit a SAVE/LOAD/activation action", received.isEmpty())
            assertArrayEquals(bytes, repo.exportBackup())
            assertEquals(epoch, repo.deliveryEpoch())
            assertEquals(history, repo.history(id))
            assertEquals(1, repo.restorePausedCount())
            assertFalse(repo.isDeliveryAllowed(id))
            assertEquals(alarms, shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.toList())
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        }
    }

    @Test fun darkImportConfirmationOnlySelectsDocument() = verifyConfirmation(ThemeChoice.DARK)
    @Test fun lightImportConfirmationOnlySelectsDocument() = verifyConfirmation(ThemeChoice.LIGHT)

    private fun verifyContent(colors: R52Colors) {
        val screen = screen(BackupUiInput(), colors)
        layout(screen)
        val texts = views(screen).filterIsInstance<TextView>()
        assertTrue(texts.any { it.text.contains("unverschlüsselt") })
        assertTrue(texts.any { it.text.contains("leeren Ereignisspeicher") })
        assertTrue(texts.any { it.text.contains("einzeln aktiviert") })
        assertTrue(texts.any { it.text.contains("persönliche Ereignisse bleiben in diesem Stand gesperrt") })
        assertTrue(texts.any { it.text.contains("nach einem Import") && it.text.contains("pausiert") })
        texts.forEach { assertReadable(it); reveal(screen, it) }
        assertTrue("Measurement and scrolling must not emit actions", received.isEmpty())
        val buttons = views(screen).filterIsInstance<Button>()
        assertEquals(listOf("Datei speichern", "Datei laden", "Testverlauf öffnen", "Schließen"),
            buttons.map { it.text.toString() })
        buttons.forEach {
            assertTrue(it.isEnabled)
            assertEquals(it.text.toString(), it.contentDescription.toString())
            assertTrue(it.width >= dp(48) && it.height >= dp(48))
            reveal(screen, it)
            it.performClick()
        }
        assertEquals(listOf(BackupUiAction.SAVE, BackupUiAction.LOAD,
            BackupUiAction.OPEN_HISTORY, BackupUiAction.CLOSE), received)
        assertTrue("Finite handset viewport must actually scroll", screen.scrollY > 0)
    }

    private fun verifyLongFailure(colors: R52Colors) {
        val message = "Wiederherstellung nicht abgeschlossen: Familienkalender_ÄrztlicheNachsorge_" +
            "Frühdienst_2026-10-30_Übergabe_東京.r52backup. " +
            "Erforderlich sind eine gültige Sicherung (höchstens 8 MiB) und ein leerer Ereignisspeicher. " +
            "Vorhandene Daten bleiben erhalten."
        val screen = screen(BackupUiInput(statusMessage = message), colors)
        layout(screen)
        val status = views(screen).filterIsInstance<TextView>().single { it.text.toString() == message }
        assertEquals(colors.text, status.currentTextColor)
        assertReadable(status)
        reveal(screen, status)
        assertTrue(received.isEmpty())
    }

    private fun verifyBusy(colors: R52Colors) {
        for ((operation, expectedStatus) in listOf(
            BackupOperation.EXPORTING to "Sicherung wird geschrieben …",
            BackupOperation.IMPORTING to "Sicherung wird geprüft …",
        )) {
            received.clear()
            val screen = screen(BackupUiInput(busy = true, operation = operation), colors)
            layout(screen)
            val texts = views(screen).filterIsInstance<TextView>()
            assertTrue(texts.any { it.text.toString() == expectedStatus })
            texts.forEach(::assertReadable)
            val buttons = texts.filterIsInstance<Button>()
            assertFalse(buttons[0].isEnabled)
            assertFalse(buttons[1].isEnabled)
            assertTrue(received.isEmpty())
            // Disabled controls must also reject a programmatic click at this boundary.
            buttons.forEach { reveal(screen, it); it.performClick() }
            assertEquals(listOf(BackupUiAction.OPEN_HISTORY, BackupUiAction.CLOSE), received)
        }
    }

    private fun verifyConfirmation(theme: ThemeChoice) {
        ThemePreferenceStore(context).write(theme)
        val id = seedQuarantinedRow()
        val before = TestAlarmRuntime.repository(context).use { it.exportBackup() }
        val controller = Robolectric.buildActivity(BackupActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        try {
            val screen = views(activity.window.decorView).filterIsInstance<BackupScreenView>().single()
            layout(screen)
            val load = views(screen).filterIsInstance<Button>().single { it.text == "Datei laden" }
            reveal(screen, load)
            load.performClick()
            val cancelled = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(cancelled.isShowing)
            assertNull(shadowOf(activity).nextStartedActivity)
            cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(activity).nextStartedActivity)
            load.performClick()
            val confirmed = ShadowAlertDialog.getLatestAlertDialog()
            confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val picker = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
            assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE))
            assertEquals("application/octet-stream", picker.type)
            assertNull(picker.data)
            TestAlarmRuntime.repository(context).use { repo ->
                assertArrayEquals("Confirmation is only document selection, never restore", before, repo.exportBackup())
                assertEquals(1, repo.restorePausedCount())
                assertFalse(repo.isDeliveryAllowed(id))
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun seedQuarantinedRow(): String {
        lateinit var id: String
        val bytes = SqliteOccurrenceRepository(context, clock, "backup-layout-source.db").use { source ->
            id = TestAlarmCoordinator(source, clock).create(1).id
            source.exportBackup()
        }
        return TestAlarmRuntime.repository(context).use { target ->
            assertEquals(1, target.restoreBackup(bytes))
            assertEquals(1, target.restorePausedCount())
            id
        }
    }

    private fun screen(input: BackupUiInput, colors: R52Colors) =
        BackupScreenView(context, BackupUiMapper.map(input), colors, received::add)

    private fun layout(view: View) {
        val width = dp(280)
        val height = dp(480)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun assertReadable(text: TextView) {
        val layout = requireNotNull(text.layout)
        val width = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
        val height = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
        assertTrue("Clipped height: ${text.text}", layout.height <= height)
        assertEquals("Missing ending: ${text.text}", text.text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertEquals("Ellipsis: ${text.text}", 0, layout.getEllipsisCount(line))
            assertTrue("Clipped width: ${text.text}", layout.getLineMax(line) <= width + 1f)
        }
    }

    private fun reveal(screen: BackupScreenView, target: View) {
        val rect = Rect(0, 0, target.width, target.height)
        screen.offsetDescendantRectToMyCoords(target, rect)
        screen.scrollTo(0, rect.top)
        val visible = Rect()
        assertTrue("Unreachable content: ${(target as? TextView)?.text}", target.getLocalVisibleRect(visible))
        assertEquals("Content needs no horizontal scrolling", target.width, visible.width())
        if (target.height <= screen.height)
            assertEquals("Control must fit vertically after scrolling", target.height, visible.height())
        else {
            screen.scrollTo(0, rect.bottom - screen.height)
            assertTrue("Long content ending must be reachable", target.getLocalVisibleRect(visible))
            assertEquals("Long content ending must not be cut off", target.height, visible.bottom)
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
