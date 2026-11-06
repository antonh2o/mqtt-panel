package ru.mqtt.panel.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал импорта/экспорта: пишется в файл, чтобы можно было
 * прислать на отладку без подключения по adb.
 * Файл: Android/data/ru.mqtt.panel/files/mqtt-import.log
 */
object ImportLog {

    private const val TAG = "ImportLog"
    private const val FILE_NAME = "mqtt-import.log"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun logFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun write(context: Context, text: String) {
        Log.i(TAG, text)
        try {
            val file = logFile(context)
            file.appendText("[${timeFormat.format(Date())}] $text\n")
        } catch (e: Exception) {
            Log.e(TAG, "Log write error", e)
        }
    }

    /** Полный путь к файлу журнала (для показа пользователю) */
    fun path(context: Context): String = logFile(context).absolutePath
}
