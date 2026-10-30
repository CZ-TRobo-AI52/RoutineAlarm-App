package com.cztr.routinealarm.ui.components

import android.content.Context
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.cztr.routinealarm.ui.accessibility.R52Accessibility
import com.cztr.routinealarm.ui.design.*
import com.cztr.routinealarm.ui.shell.AppDestination

/** Large text may scroll horizontally instead of clipping or shrinking labels. */
class R52BottomNav(context: Context, selected: AppDestination, colors: R52Colors,
    onSelect: (AppDestination) -> Unit) : HorizontalScrollView(context) {
    private var alignedViewportWidth = 0

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Each rebuilt shell keeps its selected destination visible, including large text.
        // Do not undo the user's manual scrolling on unrelated content layouts.
        if (width > 0 && width != alignedViewportWidth) {
            val row = getChildAt(0) as LinearLayout
            val active = (0 until row.childCount).map(row::getChildAt).single { it.isSelected }
            val target = when {
                active.left < scrollX -> active.left
                active.right > scrollX + width -> active.right - width
                else -> scrollX
            }
            scrollTo(target, 0)
            alignedViewportWidth = width
        }
    }

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = true
        setBackgroundColor(colors.surface)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, R52Dimensions.dp(context, 4), 0, R52Dimensions.dp(context, 4))
        }
        AppDestination.entries.forEach { destination ->
            val button = R52Button(context, destination.label, colors).apply {
                contentDescription = "${destination.label} öffnen"
                isSelected = destination == selected
                R52Accessibility.state(this, if (isSelected) "Ausgewählt" else "Nicht ausgewählt")
                setTextColor(if (isSelected) colors.accent else colors.secondaryText)
                textSize = R52Typography.SECONDARY
                ellipsize = null
                background = R52Drawables.rounded(context,
                    if (isSelected) colors.elevated else colors.surface, 12,
                    if (isSelected) colors.accent else colors.surface)
                setOnClickListener { if (destination != selected) onSelect(destination) }
            }
            row.addView(button, LinearLayout.LayoutParams(-2, -2).apply { weight = 1f })
        }
        addView(row, LayoutParams(-1, -2))
    }
}
