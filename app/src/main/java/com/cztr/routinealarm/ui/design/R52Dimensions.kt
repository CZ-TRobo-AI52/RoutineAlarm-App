package com.cztr.routinealarm.ui.design

import android.content.Context
import kotlin.math.roundToInt

object R52Dimensions {
    const val TOUCH_TARGET = 48
    const val SCREEN_PADDING = 20
    const val CARD_RADIUS = 20
    const val BUTTON_RADIUS = 16
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
