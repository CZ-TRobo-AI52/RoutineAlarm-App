package com.cztr.routinealarm.ui.shell

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.cztr.routinealarm.ui.components.R52BottomNav
import com.cztr.routinealarm.ui.design.R52Colors
import com.cztr.routinealarm.ui.design.R52Dimensions

class AppShellView(context: Context, destination: AppDestination, colors: R52Colors,
    content: View, onSelect: (AppDestination) -> Unit) : LinearLayout(context) {
    init {
        tag = "r52_shell"
        orientation = VERTICAL
        setBackgroundColor(colors.background)
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }, LayoutParams(-1, 0, 1f))
        addView(R52BottomNav(context, destination, colors, onSelect), LayoutParams(-1, -2))
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }
}
