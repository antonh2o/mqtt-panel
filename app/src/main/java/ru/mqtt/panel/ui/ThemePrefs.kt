package ru.mqtt.panel.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Переключатель темы: светлая / тёмная / как в системе.
 * Выбор хранится в SharedPreferences и применяется через
 * AppCompatDelegate.setDefaultNightMode (пересоздаёт активности сам).
 */
object ThemePrefs {

    private const val PREFS = "ui"
    private const val KEY = "theme"

    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SYSTEM) ?: SYSTEM

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (get(context)) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun save(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).apply()
        apply(context)
    }

    /** Подсветка активной кнопки темы на тулбаре: активная — ярче */
    fun alphaFor(context: Context, mode: String): Float =
        if (get(context) == mode) 1f else 0.35f
}
