package com.cztr.routinealarm.ui.plan

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.cztr.routinealarm.ui.accessibility.R52Accessibility
import com.cztr.routinealarm.ui.components.R52Button
import com.cztr.routinealarm.ui.components.R52Card
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.design.R52Dimensions
import java.time.DayOfWeek

@SuppressLint("ViewConstructor")
class PlanScreenView(
    context: Context,
    state: PlanUiState,
    colors: R52Colors,
    onPrimaryAction: () -> Unit,
    onSelectDay: (DayOfWeek) -> Unit,
    onToggleTraining: () -> Unit,
    requestedEnabled: Boolean = state.availability == PlanAvailability.ACTIVE,
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
            addView(text("WOCHENPLAN · STATUS", 12f))
            addView(text(state.title, 26f, true))
            addView(text(state.detail, 14f))
            val actionLabel = when {
                state.availability == PlanAvailability.PERMISSION_REQUIRED -> state.primaryActionLabel
                requestedEnabled -> "Wochenplan pausieren"
                else -> "Wochenplan aktivieren"
            }
            addView(R52Button(context, actionLabel, colors).apply {
                tag = "plan_primary_action"
                isSelected = requestedEnabled
                R52Accessibility.state(this, if (requestedEnabled) "Aktivierung gewählt" else "Pause gewählt")
                ellipsize = null
                setOnClickListener { onPrimaryAction() }
            })
        })

        addView(text("Woche", 21f, true))
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                state.dayChoices.forEach { choice ->
                    addView(R52Button(context, choice.label, colors).apply {
                        contentDescription = "${choice.day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.GERMAN)} auswählen"
                        isSelected = choice.selected
                        R52Accessibility.state(this, if (choice.selected) "Ausgewählt" else "Nicht ausgewählt")
                        setTextColor(if (choice.selected) colors.accent else colors.secondaryText)
                        setOnClickListener { if (!choice.selected) onSelectDay(choice.day) }
                    }, LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                }
            })
        }, LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        addView(card().apply {
            addView(text(state.dayTitle, 21f, true))
            if (state.events.isEmpty()) addView(text("Für diesen Tag sind keine persönlichen Routinen eingetragen."))
            state.events.forEach { item -> addView(itemRow(item, colors)) }
        })

        if (state.training.isNotEmpty()) addView(card().apply {
            val first = state.training.first().timeLabel
            val last = state.training.last().timeLabel
            addView(text("Training", 21f, true))
            addView(text("$first–$last Uhr · ${state.training.size} Ansagen", 14f))
            addView(R52Button(context,
                if (state.showTrainingDetails) "Training einklappen" else "Training ansehen", colors).apply {
                ellipsize = null
                setOnClickListener { onToggleTraining() }
            })
            if (state.showTrainingDetails) state.training.forEach { item -> addView(itemRow(item, colors)) }
        })
    }

    private fun itemRow(item: PlanUiItem, colors: R52Colors) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        setPadding(0, dp(10), 0, dp(10))
        addView(TextView(context).apply {
            text = item.timeLabel
            textSize = 15f
            setTextColor(colors.text)
            setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(dp(62), -2))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply { text = item.title; textSize = 16f; setTextColor(colors.text) })
            addView(TextView(context).apply { text = item.categoryLabel; textSize = 13f; setTextColor(colors.secondaryText) })
        }, LayoutParams(0, -2, 1f))
    }

    private fun dp(value: Int) = R52Dimensions.dp(context, value)
}
