package com.cztr.routinealarm.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import com.cztr.routinealarm.ui.design.*

class R52Button(context: Context, label: String, colors: R52Colors) : Button(context) {
    init {
        text = label
        contentDescription = label
        isAllCaps = false
        textSize = R52Typography.BODY
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setTextColor(colors.text)
        minimumHeight = R52Dimensions.dp(context, R52Dimensions.TOUCH_TARGET)
        minimumWidth = minimumHeight
        setPadding(R52Dimensions.dp(context, 16), R52Dimensions.dp(context, 12),
            R52Dimensions.dp(context, 16), R52Dimensions.dp(context, 12))
        background = RippleDrawable(ColorStateList.valueOf(colors.stroke),
            R52Drawables.rounded(context, colors.elevated, R52Dimensions.BUTTON_RADIUS, colors.stroke), null)
    }
}
