package ru.mqtt.panel.ui

import android.app.Activity
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
 * Строка версии между статус-баром и заголовком (окно edge-to-edge):
 * верхний padding строки = инсеты статус-бара, текст — у левого края,
 * как заголовок тулбара. По центру нельзя — на телефонах с вырезом камеры
 * текст попадает в «дырку».
 */
fun applyTopInsetToVersion(view: TextView) {
    WindowCompat.setDecorFitsSystemWindows((view.context as Activity).window, false)
    val baseTop = view.paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        if (v.paddingTop != baseTop + top) {
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
        }
        insets
    }
    ViewCompat.requestApplyInsets(view)
}
