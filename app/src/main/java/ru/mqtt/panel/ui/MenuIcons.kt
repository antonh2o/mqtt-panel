package ru.mqtt.panel.ui

import android.view.Menu

/**
 * Показ иконок у пунктов overflow-меню (⋮). Android по умолчанию рисует их
 * только у пунктов, вынесенных в тулбар; в overflow иконки скрываются, и меню
 * выглядит «голым» текстом (в референсе IoT MQTT Panel у каждого пункта есть
 * своя пиктограмма). AndroidX MenuBuilder умеет это включать, но метод
 * package-private — достаём его reflection.
 */
object MenuIcons {

    /** Название метода различается между реализациями MenuBuilder. */
    private val METHOD_NAMES = arrayOf("setOptionalIconsVisible", "setOptionalIconsVisibility")

    fun forceShowIcons(menu: Menu) {
        for (name in METHOD_NAMES) {
            try {
                val method = menu.javaClass.getDeclaredMethod(
                    name, Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(menu, true)
                return
            } catch (e: Exception) {
                // пробуем следующее имя / тихо миримся без иконок
            }
        }
    }
}
