package com.accessible.audioplayer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsSpeaker(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null
    private var confirmBeepRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    var ignoreUntil: Long = 0L
        private set

    fun setIgnoreUntil(value: Long) {
        ignoreUntil = value
    }

    fun init() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Не удалось создать ToneGenerator: ${e.message}")
        }
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("ru"))
            Log.e("MYVOSK", "TTS инициализирован")
        } else {
            Log.e("MYVOSK", "Ошибка инициализации TTS")
        }
    }

    fun speakOutShort(text: String) {
        Log.e("MYVOSK", "speakOutShort: $text")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "call")
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
        val duration = (text.length * 120L + 1500L).coerceAtLeast(2500L)
        val ttsSpeakingUntil = System.currentTimeMillis() + duration
        ignoreUntil = ttsSpeakingUntil + 800L
    }

    fun speakOut(text: String) {
        Log.e("MYVOSK", "speakOut: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        val duration = (text.length * 100L + 2000L).coerceAtLeast(3000L)
        val ttsSpeakingUntil = System.currentTimeMillis() + duration
        ignoreUntil = ttsSpeakingUntil + 1200L
    }

    fun beepListen() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Log.e("MYVOSK", "Бип: слушаю")
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
    }

    fun beepName() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Log.e("MYVOSK", "Бип: имя")
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
    }

    fun scheduleConfirmBeep() {
        confirmBeepRunnable?.let { handler.removeCallbacks(it) }
        confirmBeepRunnable = Runnable {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                handler.postDelayed({
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                }, 150)
                handler.postDelayed({
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                }, 300)
                Log.e("MYVOSK", "Бип: подтверждение (да/нет)")
                ignoreUntil = System.currentTimeMillis() + 300L
            } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
        }
        val delayMs = (ignoreUntil - System.currentTimeMillis()) + 200L
        val safeDelay = delayMs.coerceAtLeast(0L)
        handler.postDelayed(confirmBeepRunnable!!, safeDelay)
    }

    fun cancelConfirmBeep() {
        confirmBeepRunnable?.let { handler.removeCallbacks(it) }
        confirmBeepRunnable = null
    }

    fun beepError() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 250)
            Log.e("MYVOSK", "Бип: ошибка")
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
    }

    fun beepOk() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            Log.e("MYVOSK", "Бип: ок")
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
    }

    fun release() {
        confirmBeepRunnable?.let { handler.removeCallbacks(it) }
        tts?.stop()
        tts?.shutdown()
        try { toneGenerator?.release() } catch (_: Exception) {}
        toneGenerator = null
        tts = null
    }
}
