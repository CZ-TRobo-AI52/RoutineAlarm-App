package com.cztr.routinealarm.ui.components

import android.content.Context
import android.widget.LinearLayout
import com.cztr.routinealarm.ui.design.*

class R52Card(context: Context, colors: R52Colors, paddingPx: Int) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        background = R52Drawables.rounded(context, colors.surface, R52Dimensions.CARD_RADIUS, colors.stroke)
    }
}
