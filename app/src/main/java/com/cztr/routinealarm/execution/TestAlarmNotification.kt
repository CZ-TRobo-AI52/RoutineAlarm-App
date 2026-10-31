package com.cztr.routinealarm.execution

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cztr.routinealarm.occurrence.AlarmDelivery
import com.cztr.routinealarm.ui.notification.*

/** Android transport for the existing test-only menu. No clock, journal or execution authority. */
internal object TestAlarmNotification {
    const val ROUTE = "test-alarm.notification-route.v1"
    private const val ACTION = "com.cztr.routinealarm.TEST_NOTIFICATION."
    val allowed = setOf(NotificationOperation.DONE, NotificationOperation.SKIP, NotificationOperation.SNOOZE)

    fun route(token: AlarmDelivery, stage: MenuStage, choice: MenuChoice) = NotificationActionRoute(
        requireNotNull(TestAlarmRuntime.putToken(Intent(), token).dataString),
        "TEST", token.revision.toString(), stage, choice,
    )

    fun intent(context: Context, token: AlarmDelivery, route: NotificationActionRoute): Intent =
        TestAlarmRuntime.putToken(Intent(context, TestAlarmActivity::class.java), token)
            .setAction(ACTION + NotificationActionRoutes.requestKey(route))
            .putExtra(ROUTE, NotificationActionRoutes.encode(route))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun hasTransport(value: Intent): Boolean = value.hasExtra(ROUTE) || value.action?.startsWith(ACTION) == true

    /** Bind the structural route to the full canonical delivery, fixed scope and current revision.
     * Neither this check nor an immutable PendingIntent replaces transactional Core admission.
     */
    fun decode(value: Intent): NotificationActionRoute? = runCatching {
        val token = requireNotNull(TestAlarmRuntime.token(value))
        val route = requireNotNull(NotificationActionRoutes.decode(requireNotNull(value.getStringExtra(ROUTE))))
        require(route.deliveryDataUri == value.dataString && route.scopeKey == "TEST" &&
            route.planRevision == token.revision.toString())
        require(value.action == ACTION + NotificationActionRoutes.requestKey(route))
        require(NotificationActionRoutes.selection(route, allowed) != MenuSelection.Rejected)
        route
    }.getOrNull()

    fun actions(context: Context, token: AlarmDelivery): List<Notification.Action> =
        NotificationMenu.buttons(MenuStage.ROOT, allowed).map { button ->
            val target = intent(context, token, route(token, MenuStage.ROOT, button.choice))
            val pending = PendingIntent.getActivity(context, 0, target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            Notification.Action.Builder(null, button.label, pending).build()
        }
}
