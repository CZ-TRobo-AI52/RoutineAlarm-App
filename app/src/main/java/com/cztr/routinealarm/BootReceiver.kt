package com.cztr.routinealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (
            intent.action ==
            Intent.ACTION_BOOT_COMPLETED
        ) {
            // Der vollständige Wochenplan wird später
            // an dieser Stelle automatisch neu aktiviert.
        }
    }
}
