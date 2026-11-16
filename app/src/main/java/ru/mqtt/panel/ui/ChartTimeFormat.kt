package ru.mqtt.panel.ui

import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Формат подписей шкалы времени графика по длине диапазона:
 * до 5 минут — с секундами, до суток — ЧЧ:мм, дольше — даты (неделя/месяц/год
 * в формате «ЧЧ:мм» выглядели как дневная шкала).
 */
object ChartTimeFormat {

    fun pattern(spanMs: Long): String = when {
        spanMs < 5 * 60_000L -> "HH:mm:ss"
        spanMs <= 24 * 3600_000L -> "HH:mm"
        else -> "dd.MM"
    }

    fun label(spanMs: Long, timeMs: Long): String =
        SimpleDateFormat(pattern(spanMs), Locale.getDefault()).format(Date(timeMs))

    /** Подпись в подсказке под пальцем: время, а для длинных периодов — с датой */
    fun tooltipLabel(timeMs: Long): String {
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return fmt.format(Date(timeMs))
    }
}
