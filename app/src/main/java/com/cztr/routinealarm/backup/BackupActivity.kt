package com.cztr.routinealarm.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import com.cztr.routinealarm.ui.backup.BackupOperation
import com.cztr.routinealarm.ui.backup.BackupScreenView
import com.cztr.routinealarm.ui.backup.BackupUiAction
import com.cztr.routinealarm.ui.backup.BackupUiInput
import com.cztr.routinealarm.ui.backup.BackupUiMapper
import com.cztr.routinealarm.ui.shell.AppNavigationState
import com.cztr.routinealarm.ui.shell.ThemePreferenceStore
import java.util.concurrent.Executors

/** User-selected documents only. Store import and document I/O never run on the UI thread. */
class BackupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var pendingDocumentRequest: Int? = null
    private var restoreConfirmation: AlertDialog? = null
    private var operation = BackupOperation.IDLE
    private var statusMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDocumentRequest = savedInstanceState?.getInt(PENDING_DOCUMENT)
            ?.takeIf { it == EXPORT || it == IMPORT }
        render()
    }

    private fun render() {
        val colors = AppNavigationState(theme = ThemePreferenceStore(this).read())
            .colors(resources.configuration)
        val state = BackupUiMapper.map(BackupUiInput(
            busy = busy || pendingDocumentRequest != null || restoreConfirmation != null,
            operation = operation,
            statusMessage = when {
                pendingDocumentRequest != null -> "Dateiauswahl geöffnet. Auswahl abschließen oder abbrechen."
                restoreConfirmation != null -> "Wiederherstellung bestätigen oder abbrechen."
                else -> statusMessage
            },
        ))
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        setContentView(BackupScreenView(this, state, colors, ::handleBackupAction))
    }

    private fun handleBackupAction(action: BackupUiAction) {
        if (isFinishing || isDestroyed) return
        if ((busy || pendingDocumentRequest != null || restoreConfirmation != null) &&
            action in setOf(BackupUiAction.SAVE, BackupUiAction.LOAD)) return
        when (action) {
            BackupUiAction.SAVE -> chooseDocument(EXPORT)
            BackupUiAction.LOAD -> confirmRestore()
            BackupUiAction.OPEN_HISTORY -> startActivity(Intent(this, TestAlarmActivity::class.java))
            BackupUiAction.CLOSE -> finish()
        }
    }

    private fun confirmRestore() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Ereignisverlauf wiederherstellen")
            .setMessage("Nur eine eigene Sicherung laden. Vorhandener Verlauf wird nicht überschrieben. Übernommene Alarme bleiben zunächst pausiert.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Datei auswählen") { current, _ ->
                if (restoreConfirmation === current && !isFinishing && !isDestroyed) {
                    restoreConfirmation = null
                    chooseDocument(IMPORT)
                }
            }
            .create()
        restoreConfirmation = dialog
        dialog.setOnDismissListener {
            if (restoreConfirmation === dialog) {
                restoreConfirmation = null
                if (!isFinishing && !isDestroyed) render()
            }
        }
        render()
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingDocumentRequest?.let { outState.putInt(PENDING_DOCUMENT, it) }
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun chooseDocument(request: Int) {
        if (busy || pendingDocumentRequest != null || isFinishing || isDestroyed) return
        pendingDocumentRequest = request
        render()
        val intent = Intent(if (request == EXPORT) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
        if (request == EXPORT) {
            intent.putExtra(Intent.EXTRA_TITLE, "RoutineAlarm-Ereignisverlauf.r52backup")
        }
        try {
            startActivityForResult(intent, request)
        } catch (_: RuntimeException) {
            pendingDocumentRequest = null
            statusMessage = "Kein Dokumentanbieter verfügbar. Es wurden keine Daten geändert."
            render()
        }
    }

    @Deprecated("Platform document result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pendingDocumentRequest || isFinishing || isDestroyed) return
        pendingDocumentRequest = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            render()
            return
        }
        if (uri.scheme != "content") {
            statusMessage = "Bitte eine Datei über den Android-Dokumentdialog auswählen."
            render()
            return
        }
        if (busy) return
        busy = true
        operation = if (requestCode == EXPORT) BackupOperation.EXPORTING else BackupOperation.IMPORTING
        statusMessage = null
        render()
        val app = applicationContext
        worker.execute {
            val result = runCatching {
                if (requestCode == EXPORT) {
                    val bytes = TestAlarmRuntime.repository(app).use { it.exportBackup() }
                    BackupDocumentIO.write(
                        requireNotNull(app.contentResolver.openOutputStream(uri, "wt")),
                        bytes,
                    )
                    "Sicherung geschrieben. Die Datei enthält den gespeicherten Ereignisverlauf."
                } else {
                    val bytes = BackupDocumentIO.read(
                        requireNotNull(app.contentResolver.openInputStream(uri)),
                    )
                    val count = TestAlarmRuntime.repository(app).use { it.restoreBackup(bytes) }
                    "$count Ereignisse wiederhergestellt. Testwecker einzeln im Testverlauf aktivieren. Persönliche Ereignisse bleiben gesperrt."
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    busy = false
                    operation = BackupOperation.IDLE
                    statusMessage = result.getOrElse {
                        if (requestCode == EXPORT) {
                            "Sicherung nicht bestätigt. Speicherort prüfen; die ausgewählte Datei könnte unvollständig sein. Der Verlauf bleibt erhalten."
                        } else {
                            "Wiederherstellung nicht abgeschlossen. Erforderlich sind eine gültige Sicherung (höchstens 8 MiB) und ein leerer Ereignisspeicher. Vorhandene Daten bleiben erhalten."
                        }
                    }
                    render()
                }
            }
        }
    }

    override fun onDestroy() {
        restoreConfirmation?.setOnDismissListener(null)
        restoreConfirmation?.dismiss()
        restoreConfirmation = null
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val PENDING_DOCUMENT = "backup.pendingDocumentRequest"
        private const val EXPORT = 401
        private const val IMPORT = 402
    }
}
