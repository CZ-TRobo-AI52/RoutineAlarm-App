package com.cztr.routinealarm.ui.shell

import android.content.res.Configuration
import android.os.Bundle
import com.cztr.routinealarm.ui.design.R52Colors

enum class ThemeChoice { DARK, LIGHT, SYSTEM }

/** UI-only state. No domain identity, mutable occurrence, permission or action belongs here. */
data class AppNavigationState(
    var destination: AppDestination = AppDestination.TODAY,
    var theme: ThemeChoice = ThemeChoice.DARK,
) {
    fun colors(configuration: Configuration): R52Colors = when (theme) {
        ThemeChoice.DARK -> R52Colors.Dark
        ThemeChoice.LIGHT -> R52Colors.Light
        ThemeChoice.SYSTEM -> if (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES) R52Colors.Dark else R52Colors.Light
    }
    fun save(bundle: Bundle) {
        bundle.putString("r52_destination", destination.name)
        bundle.putString("r52_theme", theme.name)
    }
    companion object {
        fun restore(bundle: Bundle?) = AppNavigationState(
            AppDestination.entries.firstOrNull { it.name == bundle?.getString("r52_destination") } ?: AppDestination.TODAY,
            ThemeChoice.entries.firstOrNull { it.name == bundle?.getString("r52_theme") } ?: ThemeChoice.DARK,
        )
    }
}
