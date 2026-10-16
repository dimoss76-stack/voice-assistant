package com.accessible.audioplayer

import android.content.Context

class VoiceSettings(context: Context) {

    private val prefs = context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)

    var activationWord: String
        get() = prefs.getString("activation_word", "слушай") ?: "слушай"
        set(value) = prefs.edit().putString("activation_word", value.lowercase().trim()).apply()

    var confirmWord: String
        get() = prefs.getString("confirm_word", "говорите") ?: "говорите"
        set(value) = prefs.edit().putString("confirm_word", value.lowercase().trim()).apply()

    var timeWord: String
        get() = prefs.getString("time_word", "часы") ?: "часы"
        set(value) = prefs.edit().putString("time_word", value.lowercase().trim()).apply()

    var dateWord: String
        get() = prefs.getString("date_word", "дата") ?: "дата"
        set(value) = prefs.edit().putString("date_word", value.lowercase().trim()).apply()

    var shutdownWord: String
        get() = prefs.getString("shutdown_word", "выключись") ?: "выключись"
        set(value) = prefs.edit().putString("shutdown_word", value.lowercase().trim()).apply()

    // Режим модели: 0 = стандартная (встроенная), 1 = streaming (на телефоне)
    var modelMode: Int
        get() = prefs.getInt("model_mode", 0)
        set(value) = prefs.edit().putInt("model_mode", value).apply()

    // Показывать кнопки при входящем звонке
    var showCallButtons: Boolean
        get() = prefs.getBoolean("show_call_buttons", true)
        set(value) = prefs.edit().putBoolean("show_call_buttons", value).apply()

    // Расположение кнопок при звонке: 0 = вертикальные, 1 = горизонтальные
    var callButtonsLayout: Int
        get() = prefs.getInt("call_buttons_layout", 0)
        set(value) = prefs.edit().putInt("call_buttons_layout", value).apply()
}
