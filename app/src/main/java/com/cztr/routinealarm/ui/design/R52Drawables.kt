package com.cztr.routinealarm.ui.design

import android.content.Context
import android.graphics.drawable.GradientDrawable

object R52Drawables {
    fun rounded(context: Context, fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = R52Dimensions.dp(context, radius).toFloat()
        stroke?.let { setStroke(R52Dimensions.dp(context, 1), it) }
    }
}
