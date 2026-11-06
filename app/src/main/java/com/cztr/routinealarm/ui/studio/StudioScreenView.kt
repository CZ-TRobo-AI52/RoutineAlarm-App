package com.cztr.routinealarm.ui.studio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.cztr.routinealarm.ui.accessibility.R52Accessibility
import com.cztr.routinealarm.ui.components.R52Button
import com.cztr.routinealarm.ui.components.R52Card
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.design.R52Dimensions

@SuppressLint("ViewConstructor")
class StudioScreenView @kotlin.jvm.JvmOverloads constructor(
    context: Context,
    state: StudioUiState,
    colors: R52Colors,
    onSelectFilter: (StudioFilter) -> Unit,
    onSelectRoutine: (String) -> Unit,
    onCreateRoutine: (() -> Unit)? = null,
    onEditRoutine: ((String) -> Unit)? = null,
    onActivateRoutine: ((String) -> Unit)? = null,
    activationRoutineId: String? = null,
    activationBusy: Boolean = false,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        // The host supplies eligibility from its frozen saved-routine snapshot.
        // This View never derives permission from labels, receipts, or restored state.
        val canShowActivation = onActivateRoutine != null && activationRoutineId != null &&
            state.selected?.id == activationRoutineId
        val hasEditor = onCreateRoutine != null || onEditRoutine != null
        fun text(value: String, size: Float = 16f, strong: Boolean = false) = TextView(context).apply {
            this.text = value
            textSize = size
            setTextColor(if (strong) colors.text else colors.secondaryText)
            if (strong) setTypeface(typeface, Typeface.BOLD)
            if (R52Accessibility.isHeading(strong, size)) R52Accessibility.heading(this)
            setPadding(0, dp(4), 0, dp(8))
        }
        fun card() = R52Card(context, colors, dp(20)).apply {
            layoutParams = LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        }

        addView(card().apply {
            addView(text(if (!hasEditor && !canShowActivation) "ROUTINESTUDIO · SICHERE VORSCHAU" else "ROUTINESTUDIO", 12f))
            addView(text("Deine Routinen", 26f, true))
            addView(text(if (!hasEditor && !canShowActivation) "Dieser Stand liest den bestehenden Wochenplan. Er verändert keine Routine und erzeugt keine neue Planung." else if (hasEditor) "Routinen erstellen und bearbeiten. Speichern pausiert die Routine." else "Gespeicherte Routine ausdrücklich aktivieren.", 14f))
            onCreateRoutine?.let { create -> addView(R52Button(context, "Neue Routine", colors).apply { tag = "studio_create"; setOnClickListener { create() } }) }
        })

        addView(object : HorizontalScrollView(context) {
            private var alignedViewportWidth = 0
            private val selectedBounds = Rect()

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                if (width <= 0 || width == alignedViewportWidth) return
                val row = getChildAt(0) as? LinearLayout ?: return
                for (index in 0 until row.childCount) {
                    val selected = row.getChildAt(index)
                    if (!selected.isSelected) continue
                    selectedBounds.set(selected.left, selected.top, selected.right, selected.bottom)
                    requestChildRectangleOnScreen(row, selectedBounds, true)
                    alignedViewportWidth = width
                    break
                }
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                state.filters.forEach { filter ->
                    addView(R52Button(context, filter.label, colors).apply {
                        isSelected = filter == state.filter
                        R52Accessibility.state(this, if (isSelected) "Ausgewählt" else "Nicht ausgewählt")
                        setTextColor(if (isSelected) colors.accent else colors.secondaryText)
                        setOnClickListener { if (!isSelected) onSelectFilter(filter) }
                    }, LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                }
            })
        }, LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        addView(card().apply {
            addView(text("Routinen · ${state.items.size}", 21f, true))
            if (state.items.isEmpty()) addView(text("In diesem Filter sind keine Routinen vorhanden."))
            state.items.forEach { item ->
                addView(R52Button(context, "${item.timeLabel} · ${item.title}", colors).apply {
                    tag = "studio_routine_item"
                    contentDescription = "Routine ${item.title} öffnen"
                    ellipsize = null
                    setOnClickListener { onSelectRoutine(item.id) }
                })
                addView(text("${item.daysLabel} · ${item.categoryLabel} · ${item.statusLabel}", 13f))
            }
        })

        state.selected?.let { detail ->
            addView(card().apply {
                addView(text(if (onEditRoutine != null) "Routine bearbeiten" else if (canShowActivation) "Gespeicherte Routine" else detail.authorityLabel, 12f, true))
                addView(text(detail.title, 24f, true))
                addView(text("${detail.timeLabel} · ${detail.daysLabel}"))
                addView(text("${detail.categoryLabel} · ${detail.modeLabel} · ${detail.statusLabel}", 14f))
                addView(text("Gesprochener Text", 14f, true))
                addView(text(detail.spokenText, 14f))
                if (onEditRoutine == null && !canShowActivation) addView(text(detail.detail, 14f))
                onEditRoutine?.let { edit ->
                    addView(R52Button(context, "Bearbeiten", colors).apply {
                        tag = "studio_edit"; setOnClickListener { edit(detail.id) }
                    })
                }
                if (canShowActivation) {
                    addView(text("Aktivieren beauftragt die Planung. Eine Ausgabe ist damit nicht bestätigt.", 14f))
                    addView(R52Button(context, if (activationBusy) "Aktivierung angefragt" else "Routine aktivieren", colors).apply {
                        tag = "studio_activate"
                        layoutParams = LayoutParams(-1, -2)
                        setSingleLine(false)
                        ellipsize = null
                        isEnabled = !activationBusy
                        R52Accessibility.state(this, if (activationBusy) "Bitte warten" else "Bereit")
                        setOnClickListener {
                            // Immediate per-view double-tap guard; the host owns result/retry state.
                            if (!isEnabled || activationBusy) return@setOnClickListener
                            isEnabled = false
                            this.text = "Aktivierung angefragt"
                            contentDescription = this.text
                            R52Accessibility.state(this, "Bitte warten")
                            onActivateRoutine?.invoke(detail.id)
                        }
                    })
                }
            })
        }
    }

    private fun dp(value: Int) = R52Dimensions.dp(context, value)
}
