package com.cztr.routinealarm.ui.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cztr.routinealarm.EventCategory
import com.cztr.routinealarm.EventMode
import com.cztr.routinealarm.execution.TestAlarmReceiver
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationActionAdapterTest {
    private val token=AlarmDelivery("test:adapter-synthetic",3,7,"synthetic-epoch")
    private val now=Instant.parse("2026-10-27T08:00:00.123456789Z")
    private val allowed=NotificationOperation.entries.toSet()
    private fun intent(t:AlarmDelivery=token)=TestAlarmRuntime.putToken(
        Intent(RuntimeEnvironment.getApplication(),TestAlarmReceiver::class.java),t)
    private fun route(stage:MenuStage=MenuStage.ROOT,choice:MenuChoice=MenuChoice.DONE,t:AlarmDelivery=token)=
        NotificationActionRoute(intent(t).dataString!!,"TEST","synthetic-plan",stage,choice)
    private fun action(t:AlarmDelivery=token,type:ActionType=ActionType.DONE,minutes:Int?=null)=ExecutionAction(
        "frozen-original-action",t.occurrenceId,type,Actor.USER,now,t.revision,t.generation,minutes,"original-source","original-reason")
    private fun binding(r:NotificationActionRoute=route(),a:ExecutionAction=action())=
        FrozenActionBinding(NotificationActionRoutes.requestKey(r),a)
    private fun prepare(r:NotificationActionRoute=route(),i:Intent?=intent(),b:FrozenActionBinding?=binding(),
        ops:Set<NotificationOperation> = allowed)=NotificationActionAdapter.prepare(NotificationActionRoutes.encode(r),i,b,ops)
    private fun rejected(expected:PreparationError,value:PreparedNotification){
        assertEquals(PreparedNotification.Rejected(expected),value)
    }
    private fun sample()=RoutineOccurrence("synthetic","event","plan",LocalDate.of(2026,10,27),LocalTime.NOON,
        "UTC",0,now,now,lastUpdatedAt=now,traceId="synthetic-trace",title="Synthetic",spokenText="Synthetic",category=EventCategory.PERSONAL,mode=EventMode.ROUTINE)

    @Test fun originalFullPayloadAndExactTimestampArePreserved(){
        val a=action();val result=prepare(b=binding(a=a)) as PreparedNotification.ActionCandidate
        assertSame(a,result.originalAction);assertEquals(now,result.originalAction.occurredAt)
        assertEquals(token,result.delivery);assertEquals(NotificationActionRoutes.requestKey(route()),result.requestKey)
    }
    @Test fun identicalRetryDoesNotGenerateNewIdOrTime(){
        val b=binding();val first=prepare(b=b) as PreparedNotification.ActionCandidate
        val again=prepare(b=b) as PreparedNotification.ActionCandidate
        assertEquals(first.requestKey,again.requestKey);assertSame(first.originalAction,again.originalAction)
    }
    @Test fun missingFrozenPayloadFailsClosed(){rejected(PreparationError.MISSING_FROZEN_ACTION,prepare(b=null))}
    @Test fun changedTransportBindingIsRejected(){rejected(PreparationError.REQUEST_MISMATCH,prepare(b=binding().copy(requestKey="other")))}
    @Test fun missingOriginalExtrasAreRejected(){rejected(PreparationError.INVALID_ORIGINAL_TOKEN,prepare(i=Intent().setData(intent().data)))}
    @Test fun outerUiEnvelopeCannotReplaceCoreToken(){rejected(PreparationError.INVALID_ORIGINAL_TOKEN,
        prepare(i=intent().setData(Uri.parse(NotificationActionRoutes.encode(route())))))}
    @Test fun differentCanonicalDeliveryIsRejected(){rejected(PreparationError.DELIVERY_MISMATCH,prepare(i=intent(token.copy(epoch="other-epoch"))))}
    @Test fun changedOccurrenceGenerationOrRevisionIsRejected(){
        val a=action()
        listOf(a.copy(occurrenceId="other"),a.copy(expectedGeneration=4),a.copy(expectedRevision=8)).forEach{
            rejected(PreparationError.PAYLOAD_MISMATCH,prepare(b=binding(a=it)))
        }
    }
    @Test fun actionTypeAndSnoozeMustMatchSelection(){
        listOf(action(type=ActionType.SKIP),action(type=ActionType.SNOOZE,minutes=2),action(minutes=1)).forEach{
            rejected(PreparationError.PAYLOAD_MISMATCH,prepare(b=binding(a=it)))
        }
    }
    @Test fun invalidActorReservedIdAndBlankSourceAreRejected(){
        val a=action()
        listOf(a.copy(actor=Actor.SYSTEM),a.copy(id="schedule:reserved"),a.copy(id=""),a.copy(source=" ")).forEach{
            rejected(PreparationError.PAYLOAD_MISMATCH,prepare(b=binding(a=it)))
        }
    }
    @Test fun navigationDoesNotNeedOrEmitAnAction(){
        assertEquals(PreparedNotification.Navigate(MenuStage.SNOOZE_SHORT),prepare(r=route(choice=MenuChoice.LATER),b=null))
    }
    @Test fun outputCloseIsUnavailableAndScreenCloseIsSeparate(){
        assertTrue(prepare(r=route(MenuStage.OPTIONS,MenuChoice.CLOSE),b=null) is PreparedNotification.OutputCloseUnavailable)
        assertEquals(PreparedNotification.ScreenCloseOnly,NotificationActionAdapter.screenClose())
    }
    @Test fun originalIntentAndReturnedCopiesCannotMutateSavedTokenEvidence(){
        val original=intent().setAction("synthetic.action").apply { setDataAndType(data,"application/synthetic") }.addCategory("synthetic.category")
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val candidate=prepare(i=original) as PreparedNotification.ActionCandidate
        assertTrue(original.filterEquals(candidate.originalDelivery()))
        assertEquals(original.flags,candidate.originalDelivery().flags)
        original.putExtra("occurrence_generation",99L)
        candidate.originalDelivery().putExtra("occurrence_revision",99L)
        assertEquals(token,TestAlarmRuntime.token(candidate.originalDelivery()))
    }
    @Test fun allFiveSnoozeValuesProduceMatchingOriginalActions(){
        val choices=listOf(MenuChoice.ONE,MenuChoice.TWO,MenuChoice.THREE,MenuChoice.FOUR,MenuChoice.FIVE)
        choices.forEachIndexed{index,choice->
            val r=route(if(index<2)MenuStage.SNOOZE_SHORT else MenuStage.SNOOZE_LONG,choice)
            val a=action(type=ActionType.SNOOZE,minutes=index+1)
            assertSame(a,(prepare(r=r,b=binding(r,a)) as PreparedNotification.ActionCandidate).originalAction)
        }
        rejected(PreparationError.OPERATION_NOT_AVAILABLE,prepare(ops=emptySet()))
    }
    @Test fun historicalResultsAreAlwaysHistoricalFeedback(){
        Outcome.entries.forEach{assertEquals(JournalFeedback.HISTORICAL_ONLY,
            NotificationActionAdapter.feedback(ActionResult(it,sample(),duplicate=true)))}
    }
    @Test fun localAppliedNeverBecomesPhysicalSuccess(){
        assertEquals(JournalFeedback.LOCAL_STATE_ONLY,NotificationActionAdapter.feedback(ActionResult(Outcome.APPLIED,sample())))
        assertEquals(JournalFeedback.CONFLICT,NotificationActionAdapter.feedback(ActionResult(Outcome.CONFLICT,sample())))
        assertEquals(JournalFeedback.REJECTED,NotificationActionAdapter.feedback(ActionResult(Outcome.REJECTED,sample())))
        rejected(PreparationError.INVALID_ROUTE,NotificationActionAdapter.prepare("bad",intent(),binding(),allowed))
    }
    @Test fun existingJournalReopenReplaysSameFrozenPayloadAndRejectsChangedTime(){
        val context:Context=RuntimeEnvironment.getApplication();context.deleteDatabase("occurrences.db")
        var repo=TestAlarmRuntime.repository(context)
        try{
            val coordinator=TestAlarmCoordinator(repo,Clock.fixed(now,ZoneOffset.UTC))
            val planned=TestAlarmCoordinator(repo,Clock.fixed(now.minusSeconds(120),ZoneOffset.UTC)).create(1)
            val fired=coordinator.trigger(AlarmDelivery.from(planned,repo.deliveryEpoch()))!!
            val t=AlarmDelivery.from(fired,repo.deliveryEpoch());val r=route(t=t);val a=action(t)
            val result=prepare(r,intent(t),binding(r,a)) as PreparedNotification.ActionCandidate
            assertEquals(Outcome.APPLIED,repo.apply(result.originalAction).outcome)
            val history=repo.history(t.occurrenceId)
            repo.close();repo=TestAlarmRuntime.repository(context)
            val replay=repo.apply(result.originalAction)
            assertTrue(replay.duplicate);assertEquals(history,repo.history(t.occurrenceId))
            assertEquals(JournalFeedback.HISTORICAL_ONLY,NotificationActionAdapter.feedback(replay))
            assertThrows(ActionIdConflict::class.java){repo.apply(a.copy(occurredAt=now.plusNanos(1)))}
        }finally{repo.close();context.deleteDatabase("occurrences.db")}
    }
    @Test fun restoredHistoricalReplayCannotAuthorizeNewAction(){
        val context:Context=RuntimeEnvironment.getApplication();context.deleteDatabase("occurrences.db")
        var repo=TestAlarmRuntime.repository(context)
        try{
            val planned=TestAlarmCoordinator(repo,Clock.fixed(now.minusSeconds(120),ZoneOffset.UTC)).create(1)
            val fired=TestAlarmCoordinator(repo,Clock.fixed(now,ZoneOffset.UTC)).trigger(AlarmDelivery.from(planned,repo.deliveryEpoch()))!!
            val t=AlarmDelivery.from(fired,repo.deliveryEpoch());val r=route(MenuStage.SNOOZE_SHORT,MenuChoice.ONE,t)
            val a=action(t,ActionType.SNOOZE,1)
            val candidate=prepare(r,intent(t),binding(r,a)) as PreparedNotification.ActionCandidate
            assertEquals(Outcome.APPLIED,repo.apply(candidate.originalAction).outcome)
            val bytes=repo.exportBackup();repo.close();context.deleteDatabase("occurrences.db")
            repo=TestAlarmRuntime.repository(context);repo.restoreBackup(bytes)
            assertNotEquals(t.epoch,repo.deliveryEpoch());assertFalse(repo.isDeliveryAllowed(t.occurrenceId))
            val history=repo.history(t.occurrenceId)
            assertNull(TestAlarmRuntime.coordinator(repo).act(t,ActionType.DONE))
            val old=repo.apply(candidate.originalAction)
            assertTrue(old.duplicate);assertEquals(JournalFeedback.HISTORICAL_ONLY,NotificationActionAdapter.feedback(old))
            assertEquals(history,repo.history(t.occurrenceId))
        }finally{repo.close();context.deleteDatabase("occurrences.db")}
    }
}
