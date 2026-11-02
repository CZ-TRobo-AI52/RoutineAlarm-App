package com.cztr.routinealarm.ui.studio

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.cztr.routinealarm.*
import com.cztr.routinealarm.ui.components.R52Button
import com.cztr.routinealarm.ui.design.R52Colors
import java.time.DayOfWeek
import java.util.UUID

/** Scrollable native editor. Save callbacks carry intent, never persistence authority. */
@SuppressLint("ViewConstructor")
class StudioEditorView(context: Context, initial: StudioEditorDraft, private val colors: R52Colors,
    private val onSave: (StudioSaveRequest) -> Unit, private val onCancel: () -> Unit
) : ScrollView(context) {
    private var draft = initial
    private var status = StudioSaveStatus.IDLE
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val fields = mutableMapOf<String, EditText>()
    private val days = mutableMapOf<DayOfWeek, CheckBox>()
    private val categoryButtons = mutableMapOf<EventCategory, RadioButton>()
    private val modeButtons = mutableMapOf<EventMode, RadioButton>()
    private val message = TextView(context).apply { setTextColor(colors.text); textSize = 16f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val save = R52Button(context, "Speichern", colors).apply { tag = "studio_save" }
    private val cancel = R52Button(context, "Abbrechen", colors).apply { tag = "studio_cancel" }

    init {
        id = R.id.studio_editor
        isFillViewport = true
        setBackgroundColor(colors.background)
        body.setPadding(dp(16), dp(16), dp(16), dp(24)); addView(body, LayoutParams(-1, -2))
        label("Routine erstellen oder bearbeiten", true)
        label("Speichern pausiert diese Routine. Aktivieren erfolgt anschließend separat.")
        input("title", "Titel"); input("speech", "Sprechtext", true)
        input("hour", "Stunde (0–23)", numeric = true); input("minute", "Minute (0–59)", numeric = true)
        label("Wochentage", true)
        val labels = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
        DayOfWeek.entries.forEachIndexed { index, day ->
            val box = CheckBox(context).apply { text = labels[index]; tag = "day:" + day.name; styleChoice(this) }
            days[day] = box; body.addView(box, LinearLayout.LayoutParams(-1, -2))
        }
        label("Kategorie", true)
        val categories = listOf("Morgen", "Arbeit", "Mahlzeit", "Training", "Persönlich", "Projekt", "Abend", "Schlaf")
        val categoryGroup = RadioGroup(context)
        EventCategory.entries.forEachIndexed { index, category ->
            val button = RadioButton(context).apply { id = View.generateViewId(); text = categories[index]; tag = "category:" + category.name; styleChoice(this) }
            categoryButtons[category] = button; categoryGroup.addView(button, RadioGroup.LayoutParams(-1, -2))
        }
        body.addView(categoryGroup)
        label("Modus", true)
        val modeGroup = RadioGroup(context)
        EventMode.entries.forEach { mode ->
            val button = RadioButton(context).apply { id = View.generateViewId(); text = if(mode == EventMode.ROUTINE) "Routine" else "Ansage"; tag = "mode:" + mode.name; styleChoice(this) }
            modeButtons[mode] = button; modeGroup.addView(button, RadioGroup.LayoutParams(-1, -2))
        }
        body.addView(modeGroup); body.addView(message)
        body.addView(save, LinearLayout.LayoutParams(-1, -2)); body.addView(cancel, LinearLayout.LayoutParams(-1, -2))
        save.setOnClickListener { submit() }
        cancel.setOnClickListener { if (status == StudioSaveStatus.IDLE || status == StudioSaveStatus.ERROR || status == StudioSaveStatus.CONFLICT) onCancel() }
        populate()
    }

    private fun label(value: String, heading: Boolean = false) {
        body.addView(TextView(context).apply {
            text = value; textSize = if(heading) 20f else 16f; setTextColor(colors.text)
            setPadding(0, dp(12), 0, dp(8))
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = heading
        })
    }
    private fun input(key: String, label: String, multiline: Boolean = false, numeric: Boolean = false) {
        val view = EditText(context).apply {
            id = View.generateViewId(); tag = key; hint = label; contentDescription = label
            setTextColor(colors.text); setHintTextColor(colors.secondaryText); textSize = 18f; minHeight = dp(48)
            inputType = if(numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or
                (if(multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            setSingleLine(false)
        }
        body.addView(TextView(context).apply { text = label; setTextColor(colors.text); labelFor = view.id })
        fields[key] = view; body.addView(view, LinearLayout.LayoutParams(-1, -2))
    }
    private fun styleChoice(view: CompoundButton) {
        view.setTextColor(colors.text); view.textSize = 16f
        view.minHeight = dp(48); view.minWidth = dp(48); view.setPadding(0, dp(8), 0, dp(8))
    }
    private fun populate() {
        fields.getValue("title").setText(draft.title); fields.getValue("speech").setText(draft.spokenText)
        fields.getValue("hour").setText(draft.hour); fields.getValue("minute").setText(draft.minute)
        days.forEach { (day, view) -> view.isChecked = day in draft.days }
        categoryButtons.getValue(draft.category).isChecked = true
        modeButtons.getValue(draft.mode).isChecked = true
    }
    fun currentDraft(): StudioEditorDraft = draft.copy(title = fields.getValue("title").text.toString(),
        spokenText = fields.getValue("speech").text.toString(), hour = fields.getValue("hour").text.toString(),
        minute = fields.getValue("minute").text.toString(), days = days.filterValues { it.isChecked }.keys.toSet(),
        category = categoryButtons.entries.first { it.value.isChecked }.key, mode = modeButtons.entries.first { it.value.isChecked }.key)

    private fun submit() {
        if(status != StudioSaveStatus.IDLE && status != StudioSaveStatus.ERROR) return
        draft = currentDraft()
        val event = try { draft.event() } catch(e: IllegalArgumentException) {
            message.text = e.message; return
        }
        // Length prefixes make free text boundaries unambiguous; stable ordering preserves exact retry identity.
        val payload = listOf(event.id, event.daysOfWeek.sortedBy { it.value }.joinToString(",") { it.name },
            event.hour.toString(), event.minute.toString(), event.title, event.spokenText,
            event.category.name, event.mode.name, event.enabled.toString()).joinToString("") { "${it.length}:$it" }
        if(draft.submittedPayload != null && draft.submittedPayload != payload)
            draft = draft.copy(requestId = UUID.randomUUID().toString())
        draft = draft.copy(submittedPayload = payload)
        setSaveStatus(StudioSaveStatus.BUSY)
        try { onSave(StudioSaveRequest(event, draft.expectedVersion, draft.requestId, draft.expectedPlanVersion, draft.expectedRestoreEpoch)) }
        catch(e: Exception) { setSaveStatus(StudioSaveStatus.UNKNOWN_EFFECT) }
    }

    /** Host reports known results; CONFLICT requires reopening after explicit review, not blind retry. */
    fun setSaveStatus(value: StudioSaveStatus, detail: String? = null) {
        status = value
        val editable = value == StudioSaveStatus.IDLE || value == StudioSaveStatus.ERROR
        fun enable(view: View) { view.isEnabled = editable; if(view is ViewGroup) for(i in 0 until view.childCount) enable(view.getChildAt(i)) }
        enable(body); cancel.isEnabled = editable || value == StudioSaveStatus.CONFLICT
        message.text = detail ?: when(value) {
            StudioSaveStatus.BUSY -> "Wird gespeichert …"
            StudioSaveStatus.UNKNOWN_EFFECT -> "Speicherstatus wird geprüft. Dein Entwurf bleibt erhalten."
            StudioSaveStatus.CONFLICT -> "Die Routine wurde zwischenzeitlich geändert. Bitte neu öffnen und prüfen."
            StudioSaveStatus.ERROR -> "Speichern fehlgeschlagen. Dein Entwurf bleibt erhalten."
            StudioSaveStatus.SAVED -> "Gespeichert und pausiert."
            StudioSaveStatus.IDLE -> ""
        }
    }
    fun saveDraftState() = currentDraft().toBundle().apply {
        putString("status", status.name); putString("message", message.text.toString()); putInt("scroll", scrollY)
    }
    fun restoreDraftState(state: Bundle) {
        draft = StudioEditorDraft.fromBundle(state); populate()
        val prior = StudioSaveStatus.valueOf(state.getString("status", "IDLE"))
        setSaveStatus(if(prior == StudioSaveStatus.BUSY) StudioSaveStatus.UNKNOWN_EFFECT else prior,
            if(prior == StudioSaveStatus.BUSY) null else state.getString("message"))
        post { scrollTo(0, state.getInt("scroll")) }
    }
    override fun onSaveInstanceState(): Parcelable = saveDraftState().apply { putParcelable("super", super.onSaveInstanceState()) }
    override fun onRestoreInstanceState(state: Parcelable?) {
        if(state is Bundle) { super.onRestoreInstanceState(state.getParcelable("super")); restoreDraftState(state) }
        else super.onRestoreInstanceState(state)
    }
    override fun dispatchSaveInstanceState(container: android.util.SparseArray<Parcelable>) { dispatchFreezeSelfOnly(container) }
    override fun dispatchRestoreInstanceState(container: android.util.SparseArray<Parcelable>) { dispatchThawSelfOnly(container) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
