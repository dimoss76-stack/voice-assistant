package com.accessible.audioplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class VoiceService : Service(), org.vosk.android.RecognitionListener, TextToSpeech.OnInitListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var isActivated = false

    override fun onCreate() {
        super.onCreate()
        Log.e("MYVOSK", "0. onCreate сервиса начался")

        tts = TextToSpeech(this, this)
        Log.e("MYVOSK", "0a. TTS создан")

        showNotification()
        Log.e("MYVOSK", "0b. Уведомление создано")

        initVoskSpeechEngine()
        Log.e("MYVOSK", "0c. onCreate завершён")
    }

    override fun onInit(status: Int) {
        Log.e("MYVOSK", "onInit TTS, status = $status")
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru"))
            Log.e("MYVOSK", "Установка русского языка TTS: $result")
        } else {
            Log.e("MYVOSK", "Ошибка инициализации TTS")
        }
    }

    private fun speakOut(text: String) {
        Log.e("MYVOSK", "speakOut: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun showNotification() {
        val channelId = "voice_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Голосовое управление",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Помощник активен")
            .setContentText("Слушаю команду 'телефон'...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, notification)
    }

    private fun initVoskSpeechEngine() {
        Log.e("MYVOSK", "1. Начинаю распаковку модели model-ru")
        try {
            StorageService.unpack(this, "model-ru", "model",
                { model: Model ->
                    Log.e("MYVOSK", "2. Модель успешно загружена!")
                    this.model = model
                    startListening()
                    speakOut("Помощник готов к работе")
                },
                { exception: IOException ->
                    Log.e("MYVOSK", "2. ОШИБКА распаковки модели: ${exception.message}", exception)
                    exception.printStackTrace()
                    speakOut("Ошибка загрузки модели")
                }
            )
        } catch (e: Exception) {
            Log.e("MYVOSK", "1b. Исключение при вызове unpack: ${e.message}", e)
        }
    }

    private fun startListening() {
        Log.e("MYVOSK", "3. startListening()")
        try {
            model?.let {
                val rec = Recognizer(it, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "4. Слушание запущено")
            } ?: Log.e("MYVOSK", "3b. model == null, слушать нечего")
        } catch (e: IOException) {
            Log.e("MYVOSK", "3c. Ошибка startListening: ${e.message}", e)
        }
    }

    override fun onResult(hypothesis: String?) {
        Log.e("MYVOSK", "onResult: $hypothesis")
        if (hypothesis == null) return
        val text = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"").trim().lowercase()
        Log.e("MYVOSK", "Распознанный текст: '$text'")
        if (text.isEmpty()) return

        if (!isActivated) {
            if (text.contains("телефон")) {
                isActivated = true
                val commandAfterActivation = text.replace("телефон", "").trim()
                if (commandAfterActivation.isNotEmpty()) {
                    executeCommand(commandAfterActivation)
                    isActivated = false
                } else {
                    speakOut("Слушаю")
                }
            }
        } else {
            executeCommand(text)
            isActivated = false
        }
    }

    private fun executeCommand(command: String) {
        Log.e("MYVOSK", "executeCommand: '$command'")
        val calendar = Calendar.getInstance()

        when {
            command.contains("время") || command.contains("сколько времени") || command.contains("который час") -> {
                val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))
                val currentTime = timeFormat.format(calendar.time)
                speakOut("Сейчас $currentTime")
            }
            command.contains("день") || command.contains("дата") || command.contains("какой сегодня день") -> {
                val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
                val currentDate = dateFormat.format(calendar.time)
                speakOut("Сегодня $currentDate")
            }
            else -> {
                val intent = Intent("VOICE_COMMAND_ACTION")
                intent.putExtra("COMMAND_TEXT", command)
                sendBroadcast(intent)
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        Log.e("MYVOSK", "onPartialResult: $hypothesis")
    }

    override fun onFinalResult(hypothesis: String?) {
        Log.e("MYVOSK", "onFinalResult: $hypothesis")
    }

    override fun onError(exception: Exception?) {
        Log.e("MYVOSK", "onError: ${exception?.message}", exception)
    }

    override fun onTimeout() {
        Log.e("MYVOSK", "onTimeout")
        isActivated = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.e("MYVOSK", "onDestroy сервиса")
        speechService?.stop()
        speechService?.shutdown()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
