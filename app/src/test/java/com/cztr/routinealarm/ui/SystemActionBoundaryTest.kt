package com.cztr.routinealarm.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.cztr.routinealarm.MainActivity
import com.cztr.routinealarm.ScheduleState
import com.cztr.routinealarm.backup.BackupActivity
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.SqliteOccurrenceRepository
import com.cztr.routinealarm.occurrence.TestAlarmCoordinator
import com.cztr.routinealarm.support.awaitUi
import com.cztr.routinealarm.support.awaitUiReadersStopped
import com.cztr.routinealarm.ui.components.R52Card
import com.cztr.routinealarm.ui.components.R52BottomNav
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Clock

/** Real host action boundaries. Existing SystemLargeFontLayoutTest owns text geometry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SystemActionBoundaryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var pausedId: String

    @Before fun prepare() {
        RuntimeEnvironment.setFontScale(2f)
        app.deleteDatabase("occurrences.db")
        app.deleteDatabase("system-boundary-source.db")
        ScheduleState.setEnabled(app, false)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val clock = Clock.systemUTC()
        val backup = SqliteOccurrenceRepository(app, clock, "system-boundary-source.db").use { source ->
            pausedId = TestAlarmCoordinator(source, clock).create(5).id
            source.exportBackup()
        }
        TestAlarmRuntime.repository(app).use { target ->
            assertEquals(1, target.restoreBackup(backup))
            assertFalse(target.isDeliveryAllowed(pausedId))
        }
    }

    @After fun cleanup() {
        shadowOf(app).checkActivities(false)
        RuntimeEnvironment.setFontScale(1f)
        app.deleteDatabase("occurrences.db")
        app.deleteDatabase("system-boundary-source.db")
    }

    @Test fun missingSettingsHandlerDoesNotCrashEitherPermissionEntryPoint() = withSystem { controller ->
        val activity = controller.get()
        assertNull(activity.packageManager.resolveActivity(settingsIntent(), 0))
        shadowOf(app).checkActivities(true)
        val before = snapshot()
        val failures = mutableListOf<String>()
        for (label in listOf("Exakte Wecker erlauben", "Testwecker in 1 Minute")) {
            val error = runCatching { button(activity, label).performClick() }.exceptionOrNull()
            if (error != null) failures += "$label: ${error.javaClass.simpleName}"
            assertRequired(activity, "Exakte Wecker")
            assertPreserved(before)
        }
        assertTrue("Missing Settings handler escaped the real MainActivity callback: $failures", failures.isEmpty())
    }

    @Test fun resolvedSettingsHandlerReceivesBothEntryPointsWithoutScheduling() = withSystem { controller ->
        val activity = controller.get()
        installSettingsHandler()
        shadowOf(app).checkActivities(true)
        assertNotNull(activity.packageManager.resolveActivity(settingsIntent(), 0))
        val before = snapshot()
        for (label in listOf("Exakte Wecker erlauben", "Testwecker in 1 Minute")) {
            button(activity, label).performClick()
            assertSettingsIntent(requireNotNull(shadowOf(activity).nextStartedActivity))
            assertRequired(activity, "Exakte Wecker")
            assertPreserved(before)
        }
    }

    @Test fun settingsReturnWithPermissionDeniedKeepsActionRequired() = withSystem { controller ->
        val activity = controller.get()
        openSettings(activity)
        val before = snapshot()
        returnFromSettings(controller, granted = false)
        assertRequired(activity, "Exakte Wecker")
        assertTrue(button(activity, "Exakte Wecker erlauben").isEnabled)
        assertPreserved(before)
    }

    @Test fun settingsGrantRefreshesReadinessWithoutStartingPausedAlarms() = withSystem { controller ->
        val activity = controller.get()
        openSettings(activity)
        val before = snapshot()
        returnFromSettings(controller, granted = true)
        assertReady(activity, "Exakte Wecker")
        assertFalse(labels(card(activity, "Exakte Wecker")).contains("Exakte Wecker erlauben"))
        assertPreserved(before)
    }

    @Test fun revokedPermissionOnReturnRemovesPreviousReadyStatus() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        withSystem { controller ->
            val activity = controller.get()
            assertReady(activity, "Exakte Wecker")
            val before = snapshot()
            returnFromSettings(controller, granted = false)
            assertRequired(activity, "Exakte Wecker")
            assertPreserved(before)
        }
    }

    @Test @Config(sdk = [33]) fun deniedAndCancelledNotificationResultsDoNotGrantReadiness() = withSystem { controller ->
        val activity = controller.get()
        val before = snapshot()
        requestNotifications(activity)
        activity.onRequestPermissionsResult(100, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_DENIED))
        shadowOf(Looper.getMainLooper()).idle()
        assertRequired(activity, "Benachrichtigungen")
        requestNotifications(activity)
        activity.onRequestPermissionsResult(100, emptyArray(), intArrayOf())
        shadowOf(Looper.getMainLooper()).idle()
        assertRequired(activity, "Benachrichtigungen")
        assertPreserved(before)
    }

    @Test @Config(sdk = [33]) fun callbackWithoutPlatformGrantCannotInventNotificationSuccess() = withSystem { controller ->
        val activity = controller.get()
        val before = snapshot()
        requestNotifications(activity)
        activity.onRequestPermissionsResult(100, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertRequired(activity, "Benachrichtigungen")
        assertPreserved(before)
    }

    @Test @Config(sdk = [33]) fun realNotificationGrantRefreshesOnCallbackWithoutExtraNavigation() = withSystem { controller ->
        val activity = controller.get()
        val before = snapshot()
        requestNotifications(activity)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.onRequestPermissionsResult(100, arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED))
        shadowOf(Looper.getMainLooper()).idle()
        assertPreserved(before)
        // No navigation/rebuilt mapper/resume may hide a missing host callback refresh.
        assertReady(activity, "Benachrichtigungen")
    }

    @Test @Config(sdk = [33]) fun notificationGrantIsRecheckedOnRealResume() = withSystem { controller ->
        val activity = controller.get()
        val before = snapshot()
        controller.pause().stop()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller.start().resume().visible()
        awaitUi(activity)
        assertReady(activity, "Benachrichtigungen")
        assertPreserved(before)
    }

    @Test @Config(sdk = [31]) fun preRuntimeNotificationGateOffersNoMisleadingPermissionRequest() = withSystem { controller ->
        val activity = controller.get()
        assertReady(activity, "Benachrichtigungen")
        assertFalse(labels(card(activity, "Benachrichtigungen")).contains("Benachrichtigungen erlauben"))
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test fun navigationAndThemeCallbacksKeepPlanAndQuarantineUntouched() = withSystem { controller ->
        val activity = controller.get()
        val before = snapshot()
        button(activity, "Verlauf prüfen").performClick()
        val history = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(TestAlarmActivity::class.java.name, history.component?.className)
        assertNull(TestAlarmRuntime.token(history))
        button(activity, "Ereignisverlauf sichern / laden").performClick()
        assertEquals(BackupActivity::class.java.name,
            requireNotNull(shadowOf(activity).nextStartedActivity).component?.className)
        for ((label, choice) in listOf("Dunkles Design" to ThemeChoice.DARK,
            "Helles Design" to ThemeChoice.LIGHT, "Systemdesign" to ThemeChoice.SYSTEM)) {
            val target = views(activity.window.decorView).filterIsInstance<Button>()
                .single { it.text.toString() == label || it.text.toString() == "$label · aktiv" }
            target.performClick()
            assertEquals(choice, ThemePreferenceStore(activity).read())
            assertTrue(destination(activity).isSelected)
            assertPreserved(before)
        }
    }

    @Test fun explicitAllowedTestClickSchedulesOnlyOneNewTestAndLeavesImportedRowPaused() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        withSystem { controller ->
            val activity = controller.get()
            assertEquals(0, alarms().size)
            button(activity, "Testwecker in 1 Minute").performClick()
            val scheduled = alarms().single()
            val intent = shadowOf(scheduled.operation).savedIntent
            val token = requireNotNull(TestAlarmRuntime.token(intent))
            assertNotEquals(pausedId, token.occurrenceId)
            TestAlarmRuntime.repository(app).use { repo ->
                assertTrue(requireNotNull(repo.get(token.occurrenceId)).isTest)
                assertFalse(repo.isDeliveryAllowed(pausedId))
                assertEquals(1, repo.restorePausedCount())
            }
            assertFalse(ScheduleState.isEnabled(app))
        }
    }

    private data class Snapshot(val bytes: ByteArray, val epoch: String, val history: List<*>, val alarms: List<*>)
    private fun snapshot() = TestAlarmRuntime.repository(app).use { repo ->
        Snapshot(repo.exportBackup(), repo.deliveryEpoch(), repo.history(pausedId), alarms().toList())
    }
    private fun assertPreserved(before: Snapshot) {
        TestAlarmRuntime.repository(app).use { repo ->
            assertArrayEquals("Permission/settings action changed persisted execution data", before.bytes, repo.exportBackup())
            assertEquals(before.epoch, repo.deliveryEpoch())
            assertEquals(before.history, repo.history(pausedId))
            assertEquals(1, repo.restorePausedCount())
            assertFalse(repo.isDeliveryAllowed(pausedId))
        }
        assertFalse(ScheduleState.isEnabled(app))
        assertEquals("Permission/settings action scheduled an alarm", before.alarms, alarms().toList())
    }
    private fun alarms() = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private fun settingsIntent() = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(Uri.parse("package:${app.packageName}"))
    private fun installSettingsHandler() {
        val resolved = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
            name = "ExactAlarmSettings"; packageName = "com.android.settings"; exported = true
        } }
        shadowOf(app.packageManager).addResolveInfoForIntent(settingsIntent(), resolved)
    }
    private fun assertSettingsIntent(intent: Intent) {
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals(Uri.parse("package:${app.packageName}"), intent.data)
    }
    private fun openSettings(activity: MainActivity) {
        installSettingsHandler()
        shadowOf(app).checkActivities(true)
        button(activity, "Exakte Wecker erlauben").performClick()
        assertSettingsIntent(requireNotNull(shadowOf(activity).nextStartedActivity))
    }
    private fun returnFromSettings(controller: ActivityController<MainActivity>, granted: Boolean) {
        controller.pause().stop()
        ShadowAlarmManager.setCanScheduleExactAlarms(granted)
        controller.start().resume().visible()
        awaitUi(controller.get())
        assertTrue(destination(controller.get()).isSelected)
    }
    private fun requestNotifications(activity: MainActivity) {
        button(activity, "Benachrichtigungen erlauben").performClick()
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission)
        assertEquals(100, request.requestCode)
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
    }
    private fun assertRequired(activity: MainActivity, title: String) {
        val text = labels(card(activity, title))
        assertTrue("$title must remain action-required: $text", text.contains("AKTION NÖTIG"))
        assertFalse(text.contains("BEREIT"))
    }
    private fun assertReady(activity: MainActivity, title: String) {
        val text = labels(card(activity, title))
        assertTrue("$title must reflect actual granted permission: $text", text.contains("BEREIT"))
        assertFalse(text.contains("AKTION NÖTIG"))
    }
    private fun card(activity: MainActivity, title: String) = views(activity.window.decorView)
        .filterIsInstance<R52Card>().single { labels(it).contains(title) }
    private fun button(activity: MainActivity, label: String) = views(activity.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == label }
    private fun destination(activity: MainActivity) = views(views(activity.window.decorView)
        .filterIsInstance<R52BottomNav>().single()).filterIsInstance<Button>()
        .single { it.contentDescription == "Mehr öffnen" }
    private fun withSystem(check: (ActivityController<MainActivity>) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            awaitUi(controller.get())
            destination(controller.get()).performClick()
            check(controller)
        } finally {
            controller.pause().stop().destroy()
            awaitUiReadersStopped(controller.get())
        }
    }
    private fun labels(view: View) = views(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
