package com.cztr.routinealarm.ui.studio

import android.annotation.SuppressLint
import android.content.Context
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
class StudioScreenView(
    context: Context,
    state: StudioUiState,
    colors: R52Colors,
    onSelectFilter: (StudioFilter) -> Unit,
    onSelectRoutine: (String) -> Unit,
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
            addView(text("ROUTINESTUDIO · SICHERE VORSCHAU", 12f))
            addView(text("Deine Routinen", 26f, true))
            addView(text("Dieser Stand liest den bestehenden Wochenplan. Er verändert keine Routine und erzeugt keine neue Planung.", 14f))
        })

        addView(HorizontalScrollView(context).apply {
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
                addView(text(detail.authorityLabel, 12f, true))
                addView(text(detail.title, 24f, true))
                addView(text("${detail.timeLabel} · ${detail.daysLabel}"))
                addView(text("${detail.categoryLabel} · ${detail.modeLabel} · ${detail.statusLabel}", 14f))
                addView(text("Gesprochener Text", 14f, true))
                addView(text(detail.spokenText, 14f))
                addView(text(detail.detail, 14f))
            })
        }
    }

    private fun dp(value: Int) = R52Dimensions.dp(context, value)
}
