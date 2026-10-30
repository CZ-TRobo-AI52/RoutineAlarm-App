package com.cztr.routinealarm.ui.testalarm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
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
class TestAlarmScreenView(
    context: Context,
    state: TestAlarmUiState,
    colors: R52Colors,
    onAction: (TestAlarmUiAction) -> Unit,
) : ScrollView(context) {
    init {
        tag = "r52_test_alarm_screen"
        isFillViewport = true
        setBackgroundColor(colors.background)
        fun text(value: String, size: Float = R52Typography.BODY, strong: Boolean = false) =
            TextView(context).apply {
                text = value
                textSize = size
                setTextColor(if (strong) colors.text else colors.secondaryText)
                if (strong) setTypeface(typeface, Typeface.BOLD)
                if (R52Accessibility.isHeading(strong, size)) R52Accessibility.heading(this)
                setPadding(0, dp(4), 0, dp(8))
            }
        fun card() = R52Card(context, colors, dp(20)).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        }
        fun button(label: String, action: TestAlarmUiAction) = R52Button(context, label, colors).apply {
            minimumHeight = dp(R52Dimensions.TOUCH_TARGET)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { onAction(action) }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        when (state) {
            is TestAlarmUiState.Loading -> {
                root.addView(text(state.title, R52Typography.SCREEN_TITLE, true))
            }
            is TestAlarmUiState.Invalid -> {
                root.addView(card().apply {
                    addView(text(state.title, 26f, true))
                    addView(text(state.detail))
                })
            }
            is TestAlarmUiState.Error -> {
                root.addView(card().apply {
                    addView(text(state.title, 24f, true))
                    addView(text(state.message))
                    addView(button("Erneut versuchen", TestAlarmUiAction.RETRY))
                })
            }
            is TestAlarmUiState.Active -> {
                root.addView(card().apply {
                    addView(text("TESTWECKER", R52Typography.LABEL, true))
                    addView(text(state.title, 28f, true))
                    state.spokenText?.let { addView(text(it)) }
                    state.timing?.let { addView(text(it, R52Typography.SECONDARY)) }
                    addView(text(state.evidenceBoundary, R52Typography.SECONDARY))
                })
                if (state.active) {
                    root.addView(button("Erledigt", TestAlarmUiAction.DONE), fullWidth())
                    val snoozeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    state.snoozeMinutes.forEach { minutes ->
                        val label = if (minutes == 1) "1 Minute später" else "$minutes Minuten später"
                        snoozeRow.addView(button(label, TestAlarmUiAction.Snooze(minutes)),
                            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                    }
                    root.addView(HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(snoozeRow)
                    }, fullWidth(8))
                    root.addView(button("Überspringen", TestAlarmUiAction.SKIP), fullWidth(8))
                }
                root.addView(button("Testverlauf öffnen", TestAlarmUiAction.OPEN_HISTORY), fullWidth(8))
            }
            is TestAlarmUiState.History -> {
                root.addView(text("Testverlauf", R52Typography.SCREEN_TITLE, true))
                root.addView(text(state.intro, R52Typography.SECONDARY))
                state.pausedNotice?.let { root.addView(text(it, R52Typography.SECONDARY, true)) }
                root.addView(text(state.generalNotice, R52Typography.SECONDARY))
                state.permissionNotice?.let { root.addView(text(it, R52Typography.SECONDARY, true)) }
                state.emptyNotice?.let { root.addView(text(it)) }
                state.rows.forEach { row ->
                    root.addView(card().apply {
                        addView(text(row.headline, 19f, true))
                        addView(text(row.detail, R52Typography.SECONDARY))
                        addView(text(row.scheduling, R52Typography.SECONDARY))
                        row.pausedNotice?.let { addView(text(it, R52Typography.SECONDARY, true)) }
                        row.action?.let { action ->
                            val label = when (action) {
                                TestHistoryRowAction.ACTIVATE -> "Wiederhergestellten Test aktivieren"
                                TestHistoryRowAction.OPEN -> "Offenen Testwecker öffnen"
                                TestHistoryRowAction.REPLAN -> "Erneut planen"
                            }
                            addView(button(label, TestAlarmUiAction.HistoryRow(row.id, action)))
                        }
                    })
                }
                if (state.hasNewer) root.addView(button("Neuere Tests", TestAlarmUiAction.NEWER), fullWidth())
                if (state.hasOlder) root.addView(button("Ältere Tests", TestAlarmUiAction.OLDER), fullWidth(8))
            }
        }
        root.addView(button("Schließen", TestAlarmUiAction.CLOSE), fullWidth(12))
        addView(root)
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = R52Dimensions.dp(context, value)
}
