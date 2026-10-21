package com.cztr.routinealarm.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cztr.routinealarm.execution.TestAlarmActivity
import com.cztr.routinealarm.execution.TestAlarmRuntime
import java.util.concurrent.Executors

/** User-selected documents only. Store import and document I/O never run on the UI thread. */
class BackupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var save: Button
    private lateinit var load: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 28)
        }
        fun text(value: String, size: Float = 17f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 14, 0, 14); root.addView(this)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; minHeight = 54
            setOnClickListener { if (!busy) action() }; root.addView(this)
        }
        text("Ereignisverlauf sichern", 26f)
        text("Sichert gespeicherte Testwecker und persönliche Ereignisse mit Verlauf, Planrevisionen und Planungsrückmeldungen. Wochenplan-Einstellungen, Android-Berechtigungen und tatsächlich registrierte Alarme sind nicht enthalten.")
        text("Neue Sicherungen benötigen Alpha12 oder neuer. Ältere Sicherungen bleiben lesbar.")
        text("Die Datei ist unverschlüsselt und kann Titel und gesprochene Texte enthalten. Wähle einen privaten Speicherort. Es gibt keine automatische Übertragung.")
        status = text("Wiederherstellung ist nur in einen leeren Ereignisspeicher möglich. Offene Testwecker bleiben bis zur einzelnen Aktivierung pausiert. Importierte persönliche Ereignisse bleiben in dieser Version gesperrt.")
        save = button("Datei speichern") { chooseDocument(EXPORT) }
        load = button("Datei laden") {
            AlertDialog.Builder(this).setTitle("Ereignisverlauf wiederherstellen")
                .setMessage("Nur eine eigene Sicherung laden. Vorhandener Verlauf wird nicht überschrieben. Übernommene Alarme bleiben zunächst pausiert.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Datei auswählen") { _, _ -> chooseDocument(IMPORT) }.show()
        }
        button("Testverlauf öffnen") { startActivity(Intent(this, TestAlarmActivity::class.java)) }
        button("Schließen") { finish() }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Suppress("DEPRECATION")
    private fun chooseDocument(request: Int) {
        val intent = Intent(if (request == EXPORT) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
        if (request == EXPORT) intent.putExtra(Intent.EXTRA_TITLE, "RoutineAlarm-Ereignisverlauf.r52backup")
        try { startActivityForResult(intent, request) }
        catch (_: RuntimeException) { status.text = "Kein Dokumentanbieter verfügbar. Es wurden keine Daten geändert." }
    }

    @Deprecated("Platform document result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(EXPORT, IMPORT) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (uri.scheme != "content") {
            status.text = "Bitte eine Datei über den Android-Dokumentdialog auswählen."
            return
        }
        if (busy) return
        busy = true; save.isEnabled = false; load.isEnabled = false
        status.text = if (requestCode == EXPORT) "Sicherung wird geschrieben …" else "Sicherung wird geprüft …"
        val app = applicationContext
        worker.execute {
            val result = runCatching {
                if (requestCode == EXPORT) {
                    val bytes = TestAlarmRuntime.repository(app).use { it.exportBackup() }
                    BackupDocumentIO.write(requireNotNull(app.contentResolver.openOutputStream(uri, "wt")), bytes)
                    "Sicherung geschrieben. Die Datei enthält den gespeicherten Ereignisverlauf."
                } else {
                    val bytes = BackupDocumentIO.read(requireNotNull(app.contentResolver.openInputStream(uri)))
                    val count = TestAlarmRuntime.repository(app).use { it.restoreBackup(bytes) }
                    "$count Ereignisse wiederhergestellt. Testwecker einzeln im Testverlauf aktivieren. Persönliche Ereignisse bleiben gesperrt."
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    busy = false; save.isEnabled = true; load.isEnabled = true
                    status.text = result.getOrElse {
                        if (requestCode == EXPORT) "Sicherung nicht bestätigt. Speicherort prüfen; die ausgewählte Datei könnte unvollständig sein. Der Verlauf bleibt erhalten."
                        else "Wiederherstellung nicht abgeschlossen. Erforderlich sind eine gültige Sicherung (höchstens 8 MiB) und ein leerer Ereignisspeicher. Vorhandene Daten bleiben erhalten."
                    }
                }
            }
        }
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    companion object {
        private const val EXPORT = 401
        private const val IMPORT = 402
    }
}
