package com.cztr.routinealarm.ui.alarm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cztr.routinealarm.ui.accessibility.R52Accessibility
import com.cztr.routinealarm.ui.components.R52Button
import com.cztr.routinealarm.ui.components.R52Card
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.design.R52Dimensions
import com.cztr.routinealarm.ui.design.R52Typography

@SuppressLint("ViewConstructor")
class AlarmScreenView(
    context: Context,
    state: AlarmUiState,
    colors: R52Colors,
    onAction: (AlarmUiAction) -> Unit,
) : ScrollView(context) {
    init {
        tag = "r52_alarm_screen"
        isFillViewport = true
        setBackgroundColor(colors.background)

        fun text(value: String, size: Float = R52Typography.BODY, strong: Boolean = false) =
            TextView(context).apply {
                text = value
                textSize = size
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(if (strong) colors.text else colors.secondaryText)
                if (strong) setTypeface(typeface, Typeface.BOLD)
                if (R52Accessibility.isHeading(strong, size)) R52Accessibility.heading(this)
                setPadding(0, dp(4), 0, dp(8))
            }
        fun button(label: String, action: AlarmUiAction) = R52Button(context, label, colors).apply {
            minimumHeight = dp(R52Dimensions.TOUCH_TARGET)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { onAction(action) }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(32), dp(20), dp(28))
        }
        root.addView(text(state.clockText, 52f, true))
        root.addView(text(state.dateText, R52Typography.SECONDARY))
        root.addView(R52Card(context, colors, dp(22)).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
            addView(text("JETZT", R52Typography.LABEL, true))
            addView(text(state.title, 28f, true))
            state.spokenText?.let { addView(text(it, R52Typography.BODY)) }
            state.warning?.let { addView(text(it, R52Typography.SECONDARY, true)) }
        })

        root.addView(button("Erledigt", AlarmUiAction.DONE), fullWidth(top = 20))

        if (state.snoozeMinutes.isNotEmpty()) {
            root.addView(text(
                if (state.pendingSnoozeMinutes == null) "Später erinnern" else "Unklare Verschiebung erneut versuchen",
                R52Typography.SECTION_TITLE,
                true,
            ), fullWidth(top = 18))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            state.snoozeMinutes.forEach { minutes ->
                val label = when {
                    state.pendingSnoozeMinutes != null -> "$minutes Min. erneut"
                    minutes == 1 -> "1 Minute später"
                    else -> "$minutes Minuten später"
                }
                row.addView(button(label, AlarmUiAction.Snooze(minutes)), LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(8)
                })
            }
            root.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }, fullWidth(top = 8))
            root.addView(text(state.snoozeBoundary, R52Typography.SECONDARY), fullWidth(top = 4))
        }

        root.addView(button("Überspringen", AlarmUiAction.SKIP), fullWidth(top = 12))
        if (state.showCloseOutput) {
            root.addView(button("Nur Ausgabe schließen", AlarmUiAction.CLOSE_OUTPUT), fullWidth(top = 8))
        }
        root.addView(button("Nur Anzeige schließen", AlarmUiAction.CLOSE_SCREEN), fullWidth(top = 8))
        root.addView(text(state.evidenceBoundary, R52Typography.SECONDARY), fullWidth(top = 12))
        addView(root)
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = R52Dimensions.dp(context, value)
}
