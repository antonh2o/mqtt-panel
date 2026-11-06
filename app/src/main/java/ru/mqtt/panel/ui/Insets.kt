package ru.mqtt.panel.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

/**
 * Android 15+ включает edge-to-edge принудительно: без обработки контент уезжает
 * под статус-бар, и заголовок «MQTT Панель» тонет в часах.
 *
 * Основной механизм — `android:fitsSystemWindows="true"` на корне layout: система
 * сама превращает инсеты в padding корня. Здесь же — подстраховка для случаев,
 * когда корень инсеты не получил (например, хендлер перехватил их раньше).
 */

/** Отступ сверху под статус-бар, если корень его не забрал */
fun applyTopInsetToToolbar(activity: Activity, toolbar: MaterialToolbar) {
    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    val minHeight = toolbar.minimumHeight
    ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        if (top > 0 && v.paddingTop != top) {
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            if (minHeight > 0) v.minimumHeight = minHeight + top
        }
        insets
    }
    ViewCompat.requestApplyInsets(toolbar)
}

/** Отступ снизу под навигацию, если корень его не забрал */
fun applyBottomInset(activity: Activity, view: View) {
    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    val baseBottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottom + bottom)
        insets
    }
    ViewCompat.requestApplyInsets(view)
}

/**
 * Строка версии в полосе статус-бара: окно edge-to-edge, высота строки = инсеты
 * статус-бара, текст — по центру полосы (слева часы, справа иконки — край заняты).
 * Вызывать вместо fitsSystemWindows на экране: тулбару тогда нужен applyTopInsetToToolbar.
 */
fun applyTopInsetToVersionStrip(view: TextView) {
    WindowCompat.setDecorFitsSystemWindows((view.context as Activity).window, false)
    ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        if (top > 0 && view.minimumHeight != top) {
            view.minimumHeight = top
            view.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        }
        insets
    }
    ViewCompat.requestApplyInsets(view)
}
