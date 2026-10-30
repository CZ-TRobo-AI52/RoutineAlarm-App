package com.cztr.routinealarm.ui.history

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

@SuppressLint("ViewConstructor")
class HistoryScreenView(
    context: Context,
    state: HistoryUiState,
    colors: R52Colors,
    onOpenTestHistory: () -> Unit,
    onRetry: () -> Unit,
    onOpenSystem: () -> Unit,
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
        addView(card().apply {
            addView(text("VERLAUF · EVIDENZGRENZEN", 12f))
            addView(text(state.testTitle, 24f, true))
            addView(text(state.testDetail, 14f))
            addView(text(state.evidenceBoundary, 14f))
            if (state.showRetry) addView(R52Button(context, "Erneut versuchen", colors).apply {
                ellipsize = null
                setOnClickListener { onRetry() }
            })
            if (state.showPermissionAction) addView(R52Button(context, "System prüfen", colors).apply {
                ellipsize = null
                setOnClickListener { onOpenSystem() }
            })
            addView(R52Button(context, "Testverlauf öffnen", colors).apply {
                ellipsize = null
                setOnClickListener { onOpenTestHistory() }
            })
        })
        addView(card().apply {
            addView(text("Persönliche Routinen", 20f, true))
            addView(text("Diese Oberfläche besitzt noch keine gemeinsame persönliche Occurrence-Historie. Der bestehende Wochenplan wird deshalb nicht als erledigter Verlauf dargestellt.", 14f))
        })
        addView(card().apply {
            addView(text("Training", 20f, true))
            addView(text("Ein belastbarer Sessionverlauf wird erst nach einem persistenten Trainingscursor und gemeinsamer Outcome-Semantik angezeigt.", 14f))
        })
    }

    private fun dp(value: Int) = R52Dimensions.dp(context, value)
}
