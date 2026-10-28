package com.cztr.routinealarm.ui.notification

import android.content.Intent
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.occurrence.*

data class FrozenActionBinding(val requestKey:String,val originalAction:ExecutionAction)
enum class PreparationError { INVALID_ROUTE,INVALID_ORIGINAL_TOKEN,DELIVERY_MISMATCH,OPERATION_NOT_AVAILABLE,MISSING_FROZEN_ACTION,REQUEST_MISMATCH,PAYLOAD_MISMATCH }
enum class JournalFeedback { HISTORICAL_ONLY,LOCAL_STATE_ONLY,REJECTED,CONFLICT }
sealed interface PreparedNotification {
    data class Rejected(val reason:PreparationError):PreparedNotification
    data class Navigate(val stage:MenuStage):PreparedNotification
    data object ScreenCloseOnly:PreparedNotification
    data class OutputCloseUnavailable(val requestKey:String,val delivery:AlarmDelivery):PreparedNotification
    class ActionCandidate internal constructor(val requestKey:String,val delivery:AlarmDelivery,
        val scopeKey:String,val planRevision:String,val originalAction:ExecutionAction,original:Intent):PreparedNotification {
        private val evidence=Intent(original)
        fun originalDelivery():Intent=Intent(evidence)
    }
}
object NotificationActionAdapter {
    /** Structural preparation only. An authorized Core consumer must revalidate current state.
     * The original full action must be recovered by that consumer; no UUID, clock or store lives here.
     */
    fun prepare(routeData:String?,original:Intent?,frozen:FrozenActionBinding?,allowed:Set<NotificationOperation>):PreparedNotification {
        fun reject(error:PreparationError)=PreparedNotification.Rejected(error)
        val route=routeData?.let(NotificationActionRoutes::decode)
            ?:return reject(PreparationError.INVALID_ROUTE)
        val snapshot=original?.let(::Intent) ?:return reject(PreparationError.INVALID_ORIGINAL_TOKEN)
        val token=try { TestAlarmRuntime.token(snapshot) } catch (_:RuntimeException) { null }
            ?:return reject(PreparationError.INVALID_ORIGINAL_TOKEN)
        if(snapshot.dataString!=route.deliveryDataUri)return reject(PreparationError.DELIVERY_MISMATCH)
        val selection=NotificationActionRoutes.selection(route,allowed)
        if(selection is MenuSelection.Navigate)return PreparedNotification.Navigate(selection.stage)
        if(selection !is MenuSelection.Request)return reject(PreparationError.OPERATION_NOT_AVAILABLE)
        val key=NotificationActionRoutes.requestKey(route)
        if(selection.operation==NotificationOperation.CLOSE_OUTPUT)
            return PreparedNotification.OutputCloseUnavailable(key,token)
        val binding=frozen ?:return reject(PreparationError.MISSING_FROZEN_ACTION)
        if(binding.requestKey!=key)return reject(PreparationError.REQUEST_MISMATCH)
        val expected=when(selection.operation){
            NotificationOperation.DONE->ActionType.DONE
            NotificationOperation.SKIP->ActionType.SKIP
            NotificationOperation.SNOOZE->ActionType.SNOOZE
            NotificationOperation.CLOSE_OUTPUT->error("Handled before action binding")
        }
        val action=binding.originalAction
        val validTime=try { action.occurredAt.toEpochMilli();true } catch (_:ArithmeticException) { false }
        if(action.id.isBlank() || action.id.startsWith("schedule:") || action.source.isBlank() ||
            action.actor!=Actor.USER || !validTime || action.occurrenceId!=token.occurrenceId ||
            action.expectedRevision!=token.revision || action.expectedGeneration!=token.generation ||
            action.type!=expected || action.snoozeMinutes!=selection.snoozeMinutes)
            return reject(PreparationError.PAYLOAD_MISMATCH)
        return PreparedNotification.ActionCandidate(key,token,route.scopeKey,route.planRevision,action,snapshot)
    }
    fun screenClose():PreparedNotification=PreparedNotification.ScreenCloseOnly
    /** A journal result never proves a physical effect or grants fresh execution authority. */
    fun feedback(result:ActionResult):JournalFeedback=when {
        result.duplicate->JournalFeedback.HISTORICAL_ONLY
        result.outcome==Outcome.APPLIED->JournalFeedback.LOCAL_STATE_ONLY
        result.outcome==Outcome.CONFLICT->JournalFeedback.CONFLICT
        else->JournalFeedback.REJECTED
    }
}
