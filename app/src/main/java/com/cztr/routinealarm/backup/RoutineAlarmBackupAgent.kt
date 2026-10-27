package com.cztr.routinealarm.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import com.cztr.routinealarm.ScheduleState

/** Auto Backup retains the platform file transport and XML exclusions. */
class RoutineAlarmBackupAgent : BackupAgent() {
    // fullBackupOnly=true, minSdk=26: the app does not implement key/value backup.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput,
        newState: ParcelFileDescriptor) = Unit

    override fun onRestore(data: BackupDataInput, appVersionCode: Int,
        newState: ParcelFileDescriptor?) = Unit

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        // Handles old snapshots too. Do not open the occurrence DB or start Android outputs here.
        // Commit before the restore callback returns; never silently acknowledge a failed write.
        ScheduleState.disableAfterRestore(this)
    }
}
