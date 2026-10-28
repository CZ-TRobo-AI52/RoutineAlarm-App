package com.cztr.routinealarm.ui.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import com.cztr.routinealarm.execution.TestAlarmReceiver
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.AlarmDelivery
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Characterization only: handles are never sent and no product adapter is installed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31])
class NotificationAndroidTransportTest {
    private lateinit var context: Context
    private val handles = mutableSetOf<PendingIntent>()
    private val token = AlarmDelivery("synthetic:id / ü", 3, 7, "synthetic-epoch")

    @Before fun setup() { context = RuntimeEnvironment.getApplication() }

    @After fun cleanupAndCheckNoDispatch() {
        handles.forEach { it.cancel() }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        assertFalse(context.databaseList().contains("occurrences.db"))
    }

    private fun coreIntent(value: AlarmDelivery = token): Intent = TestAlarmRuntime.putToken(
        Intent(context, TestAlarmReceiver::class.java), value)

    private fun route(stage: MenuStage = MenuStage.ROOT, choice: MenuChoice = MenuChoice.DONE) =
        NotificationActionRoute(coreIntent().dataString!!, "TEST", "synthetic-plan-7", stage, choice)

    private fun uiIntent(value: NotificationActionRoute): Intent =
        Intent(context, TestAlarmReceiver::class.java)
            .setData(Uri.parse(NotificationActionRoutes.encode(value)))

    private fun pending(intent: Intent): PendingIntent = PendingIntent.getBroadcast(context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)!!.also { handles.add(it) }

    private fun lookup(intent: Intent): PendingIntent? = PendingIntent.getBroadcast(context, 0, intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun parcelRoundtrip(intent: Intent): Intent {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    @Test fun canonicalCoreTokenSurvivesAndroidParcel() {
        assertEquals(token, TestAlarmRuntime.token(parcelRoundtrip(coreIntent())))
    }

    @Test fun missingCoreRevisionIsRejectedAfterParcel() {
        val value = coreIntent().apply { removeExtra("occurrence_revision") }
        assertNull(TestAlarmRuntime.token(parcelRoundtrip(value)))
    }

    @Test fun changedCoreGenerationIsRejectedAfterParcel() {
        val value = coreIntent().putExtra("occurrence_generation", token.generation + 1)
        assertNull(TestAlarmRuntime.token(parcelRoundtrip(value)))
    }

    @Test fun outerUiUriIsNotDirectCoreDelivery() {
        val value = coreIntent().setData(Uri.parse(NotificationActionRoutes.encode(route())))
        assertNull(TestAlarmRuntime.token(parcelRoundtrip(value)))
    }

    @Test fun decodingRecoversExactInnerUriWithoutCreatingAuthorityAdapter() {
        val value = parcelRoundtrip(uiIntent(route()))
        val decoded = NotificationActionRoutes.decode(value.dataString!!)!!
        assertEquals(coreIntent().dataString, decoded.deliveryDataUri)
        assertNull(TestAlarmRuntime.token(Intent().setData(Uri.parse(decoded.deliveryDataUri))))
        val restored = coreIntent().setData(Uri.parse(decoded.deliveryDataUri))
        assertEquals(token, TestAlarmRuntime.token(restored))
        // This is structural parsing only: no repository lookup or admission was performed.
    }

    @Test fun differentExtrasAloneCollideAtSameRequestCode() {
        val first = coreIntent().putExtra("ui_choice", "DONE")
        val second = coreIntent().putExtra("ui_choice", "SKIP")
        assertTrue(first.filterEquals(second))
        assertEquals(pending(first), pending(second))
    }

    @Test fun updateCurrentChangesCreatorExtrasDespiteImmutableHandle() {
        val handle = pending(coreIntent().putExtra("ui_choice", "DONE"))
        val updated = pending(coreIntent().putExtra("ui_choice", "SKIP"))
        assertEquals(handle, updated)
        assertEquals("SKIP", shadowOf(handle).savedIntent.getStringExtra("ui_choice"))
    }

    @Test fun canonicalCoreDataSeparatesOccurrenceGenerationRevisionAndEpoch() {
        val original = pending(coreIntent())
        listOf(token.copy(occurrenceId = "other"), token.copy(generation = 4),
            token.copy(revision = 8), token.copy(epoch = "next-epoch")).forEach {
            assertNotEquals(original, pending(coreIntent(it)))
        }
        assertEquals(5, handles.size)
    }

    @Test fun differentUiChoicesHaveDifferentAndroidHandles() {
        assertNotEquals(pending(uiIntent(route())),
            pending(uiIntent(route(choice = MenuChoice.LATER))))
    }

    @Test fun allFiveSnoozeChoicesHaveSeparateAndroidHandles() {
        val choices = listOf(MenuStage.SNOOZE_SHORT to MenuChoice.ONE,
            MenuStage.SNOOZE_SHORT to MenuChoice.TWO,
            MenuStage.SNOOZE_LONG to MenuChoice.THREE,
            MenuStage.SNOOZE_LONG to MenuChoice.FOUR,
            MenuStage.SNOOZE_LONG to MenuChoice.FIVE)
        assertEquals(5, choices.map { pending(uiIntent(route(it.first, it.second))) }.toSet().size)
    }

    @Test fun navigatingBackCannotAliasTheRootOptionsHandle() {
        assertNotEquals(pending(uiIntent(route(choice = MenuChoice.OPTIONS))),
            pending(uiIntent(route(MenuStage.OPTIONS, MenuChoice.BACK))))
    }

    @Test fun scopeAndPlanRevisionRemainPartOfAndroidIdentity() {
        val original = route()
        val values = listOf(original, original.copy(scopeKey = "PERSON:synthetic-A"),
            original.copy(planRevision = "synthetic-plan-8"))
        assertEquals(3, values.map { pending(uiIntent(it)) }.toSet().size)
    }

    @Test fun exactUiRouteSurvivesAndroidParcelAndPendingIntentStorage() {
        val original = route(MenuStage.OPTIONS, MenuChoice.CLOSE)
        val handle = pending(parcelRoundtrip(uiIntent(original)))
        assertEquals(original, NotificationActionRoutes.decode(shadowOf(handle).savedIntent.dataString!!))
    }

    @Test fun cancellingOneUiHandlePreservesOtherChoice() {
        val done = uiIntent(route())
        val later = uiIntent(route(choice = MenuChoice.LATER))
        val completed = pending(done)
        val retained = pending(later)
        completed.cancel()
        assertNull(lookup(done))
        assertEquals(retained, lookup(later))
    }

    @Test fun AndroidStoredValidRouteDoesNotOverrideRemovedOperation() {
        val handle = pending(uiIntent(route()))
        val decoded = NotificationActionRoutes.decode(shadowOf(handle).savedIntent.dataString!!)!!
        assertEquals(MenuSelection.Rejected, NotificationActionRoutes.selection(decoded, emptySet()))
    }

    @Test fun noCreateLookupCannotFindAChangedScopeIdentity() {
        val original = route()
        pending(uiIntent(original))
        assertNull(lookup(uiIntent(original.copy(scopeKey = "PERSON:synthetic-B"))))
    }
}
