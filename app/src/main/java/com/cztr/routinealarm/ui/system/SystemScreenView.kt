package com.cztr.routinealarm.ui.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import com.cztr.routinealarm.ui.accessibility.R52Accessibility
import com.cztr.routinealarm.ui.components.R52Button
import com.cztr.routinealarm.ui.components.R52Card
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.design.R52Dimensions
import com.cztr.routinealarm.ui.shell.ThemeChoice

@SuppressLint("ViewConstructor")
class SystemScreenView(
    context: Context,
    state: SystemUiState,
    colors: R52Colors,
    onAction: (SystemAction) -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL

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
        fun button(label: String, action: SystemAction) = R52Button(context, label, colors).apply {
            minimumHeight = dp(R52Dimensions.TOUCH_TARGET)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setOnClickListener { onAction(action) }
        }
        fun statusLabel(status: SystemStatus) = when (status.readiness) {
            SystemReadiness.READY -> "BEREIT"
            SystemReadiness.ACTION_REQUIRED -> "AKTION NÖTIG"
            SystemReadiness.PAUSED -> "PAUSIERT"
            SystemReadiness.UNKNOWN -> "NICHT BESTÄTIGT"
        }
        fun addStatus(status: SystemStatus) {
            addView(card().apply {
                addView(text(statusLabel(status), 12f, true))
                addView(text(status.title, 20f, true))
                addView(text(status.detail, 14f))
                if (status.action != null && status.actionLabel != null) {
                    addView(button(status.actionLabel, status.action))
                }
            })
        }

        addStatus(state.notifications)
        addStatus(state.exactAlarms)
        addStatus(state.weeklyPlan)
        addStatus(state.recovery)
        addStatus(state.backup)

        addView(card().apply {
            addView(text("TEST & DIAGNOSE", 12f, true))
            addView(text("Testwecker", 20f, true))
            addView(text("Plant über den bestehenden Testalarm-Pfad. Planung und tatsächliche Ausgabe bleiben getrennte Evidenz.", 14f))
            addView(button("Testwecker in 1 Minute", SystemAction.SCHEDULE_TEST_ALARM))
        })

        addView(card().apply {
            addView(text("DARSTELLUNG", 12f, true))
            addView(text("Design", 20f, true))
            val themes = listOf(
                Triple("Dunkles Design", ThemeChoice.DARK, SystemAction.SET_THEME_DARK),
                Triple("Helles Design", ThemeChoice.LIGHT, SystemAction.SET_THEME_LIGHT),
                Triple("Systemdesign", ThemeChoice.SYSTEM, SystemAction.SET_THEME_SYSTEM),
            )
            themes.forEach { (label, choice, action) ->
                val selected = state.theme == choice
                addView(button(if (selected) "$label · aktiv" else label, action).apply {
                    isSelected = selected
                    R52Accessibility.state(this, if (selected) "Ausgewählt" else "Nicht ausgewählt")
                })
            }
        })

        addView(card().apply {
            addView(text("BUILD", 12f, true))
            addView(text(state.versionName, 20f, true))
            addView(text(state.packageName, 14f))
            addView(text(state.buildClaim, 14f))
        })
    }

    private fun dp(value: Int) = R52Dimensions.dp(context, value)
}
