package com.cztr.routinealarm.ui.shell

import android.content.Context

/** Persists presentation preference only. It grants no execution or domain authority. */
internal class ThemePreferenceStore internal constructor(
    private val readValue: () -> String?,
    private val writeValue: (String) -> Unit,
) {
    constructor(context: Context) : this(
        readValue = {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
        },
        writeValue = { value ->
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME, value).apply()
        },
    )

    fun read(default: ThemeChoice = ThemeChoice.DARK): ThemeChoice =
        ThemeChoice.entries.firstOrNull { it.name == readValue() } ?: default

    fun write(choice: ThemeChoice) {
        writeValue(choice.name)
    }

    companion object {
        const val PREFERENCES_NAME = "r52_ui_preferences"
        private const val KEY_THEME = "theme"
    }
}
