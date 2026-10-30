package com.cztr.routinealarm.ui.backup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
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
class BackupScreenView(
    context: Context,
    state: BackupUiState,
    colors: R52Colors,
    onAction: (BackupUiAction) -> Unit,
) : ScrollView(context) {
    init {
        tag = "r52_backup_screen"
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
        fun button(label: String, action: BackupUiAction, enabled: Boolean = true) =
            R52Button(context, label, colors).apply {
                isEnabled = enabled
                minimumHeight = dp(R52Dimensions.TOUCH_TARGET)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { if (isEnabled) onAction(action) }
            }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        root.addView(text(state.title, R52Typography.SCREEN_TITLE, true))
        root.addView(card().apply {
            addView(text("UMFANG", R52Typography.LABEL, true))
            addView(text(state.description, R52Typography.SECONDARY))
            addView(text(state.securityWarning, R52Typography.SECONDARY, true))
            addView(text(state.restoreBoundary, R52Typography.SECONDARY))
        })
        root.addView(card().apply {
            addView(text("STATUS", R52Typography.LABEL, true))
            addView(text(state.status, R52Typography.BODY, true))
        })
        root.addView(button("Datei speichern", BackupUiAction.SAVE, state.saveEnabled), fullWidth())
        root.addView(button("Datei laden", BackupUiAction.LOAD, state.loadEnabled), fullWidth(8))
        root.addView(button("Testverlauf öffnen", BackupUiAction.OPEN_HISTORY), fullWidth(8))
        root.addView(button("Schließen", BackupUiAction.CLOSE), fullWidth(8))
        addView(root)
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = R52Dimensions.dp(context, value)
}
