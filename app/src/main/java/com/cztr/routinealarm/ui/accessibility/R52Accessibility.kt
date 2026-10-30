package com.cztr.routinealarm.ui.accessibility

import android.os.Build
import android.view.View

/** Shared semantic helpers. Visual state must never be the sole accessibility signal. */
object R52Accessibility {
    fun isHeading(strong: Boolean, size: Float): Boolean = strong && size >= 18f

    fun heading(view: View) {
        if (Build.VERSION.SDK_INT >= 28) {
            view.isAccessibilityHeading = true
        }
    }

    fun state(view: View, description: String) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.stateDescription = description
        } else {
            val current = view.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            view.contentDescription = listOfNotNull(current, description).joinToString(". ")
        }
    }
}
