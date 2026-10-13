package com.accessible.audioplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var settings: VoiceSettings

    private var awaitingAnswer = false
    private var ttsSpeakingUntil = 0L
    private var lastActivationTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val resetActivation = Runnable {
        Log.e("MYVOSK", "Таймаут — сбрасываю активацию")
        isActivated = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.e("MYVOSK", "0. onCreate сервиса начался")
        settings = VoiceSettings(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAssistant::WakeLock")
        wakeLock?.acquire()

        tts = TextToSpeech(this, this)
        showNotification()
        initVoskSpeechEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "INCOMING_CALL" -> {
                val callerName = intent.getStringExtra("CALLER_NAME") ?: "Неизвестный номер"
                handleIncomingCall(callerName)
            }
            "CALL_ANSWERED" -> {
                awaitingAnswer = false
            }
            "CALL_ENDED" -> {
                awaitingAnswer = false
            }
        }
        return START_STICKY
    }

    private fun handleIncomingCall(callerName: String) {
        awaitingAnswer = true
        speakOut("Звонит $callerName. Ответить?")
    }

    private fun acceptCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                telecomManager.acceptRingingCall()
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка приёма: ${e.message}", e)
        }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                telecomManager.endCall()
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка отклонения: ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("ru"))
        }
    }

    private fun speakOut(text: String) {
        Log.e("MYVOSK", "speakOut: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        val duration = (text.length * 70L + 1500L).coerceAtLeast(2500L)
        ttsSpeakingUntil = System.currentTimeMillis() + duration
    }

    private fun showNotification() {
        val channelId = "voice_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Голосовое управление", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Помощник активен")
            .setContentText("Скажите '${settings.activationWord}' для активации")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun initVoskSpeechEngine() {
        try {
            StorageService.unpack(this, "model-ru", "model",
                { model: Model ->
                    this.model = model
                    startListening()
                    speakOut("Помощник готов")
                },
                { exception: IOException ->
                    Log.e("MYVOSK", "Ошибка модели: ${exception.message}", exception)
                }
            )
        } catch (e: Exception) {
            Log.e("MYVOSK", "Исключение: ${e.message}", e)
        }
    }

    private fun startListening() {
        try {
            model?.let {
                val rec = Recognizer(it, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
            }
        } catch (e: IOException) {
            Log.e("MYVOSK", "Ошибка startListening: ${e.message}", e)
        }
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis == null) return
        val text = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"").trim().lowercase()
        if (text.isEmpty()) return

        Log.e("MYVOSK", "Распознано: '$text'")

        if (System.currentTimeMillis() < ttsSpeakingUntil) return

        val activation = settings.activationWord
        val confirm = settings.confirmWord
        val timeWord = settings.timeWord
        val dateWord = settings.dateWord
        val shutdownWord = settings.shutdownWord

        // === Голосовое выключение ===
        if (text.contains(shutdownWord)) {
            speakOut("Выключаюсь")
            handler.postDelayed({
                stopSelf()
                System.exit(0)
            }, 1500)
            return
        }

        // === Без активации: часы и дата ===
        if (!isActivated && !awaitingAnswer) {
            if (text.contains(timeWord)) {
                val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))
                speakOut("Сейчас ${timeFormat.format(Calendar.getInstance().time)}")
                return
            }
            if (text.contains(dateWord) || text.contains("день")) {
                val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
                speakOut("Сегодня ${dateFormat.format(Calendar.getInstance().time)}")
                return
            }
        }

        // === Звонок — ожидание ответа ===
        if (awaitingAnswer) {
            val words = text.split(" ", ",", ".", "!", "?")
            if (words.contains("да") || words.contains("ответить") || words.contains("ответь")) {
                speakOut("Отвечаю")
                acceptCall()
                awaitingAnswer = false
                return
            }
            if (words.contains("нет") || words.contains("сбрось") || words.contains("отклони")) {
                speakOut("Отклоняю")
                rejectCall()
                awaitingAnswer = false
                return
            }
            return
        }

        // === Обычная активация ===
        if (!isActivated) {
            if (text.contains(activation)) {
                if (System.currentTimeMillis() - lastActivationTime < 3000) return
                lastActivationTime = System.currentTimeMillis()
                isActivated = true
                val cmd = text.replace(activation, "").trim()
                if (cmd.isNotEmpty()) {
                    executeCommand(cmd)
                    isActivated = false
                } else {
                    speakOut(confirm)
                    handler.removeCallbacks(resetActivation)
                    handler.postDelayed(resetActivation, 15000L)
                }
            }
        } else {
            if (text == activation) return
            executeCommand(text)
            handler.removeCallbacks(resetActivation)
            isActivated = false
        }
    }

    private fun executeCommand(command: String) {
        Log.e("MYVOSK", "executeCommand: '$command'")
        val calendar = Calendar.getInstance()

        when {
            command.contains("время") || command.contains("сколько времени") || command.contains("который час") -> {
                val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))
                speakOut("Сейчас ${timeFormat.format(calendar.time)}")
            }
            command.contains("день") || command.contains("дата") -> {
                val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
                speakOut("Сегодня ${dateFormat.format(calendar.time)}")
            }
            else -> {
                speakOut("Не понял")
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { Log.e("MYVOSK", "onError: ${exception?.message}", exception) }
    override fun onTimeout() {}
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(resetActivation)
        wakeLock?.let { if (it.isHeld) it.release() }
        speechService?.stop()
        speechService?.shutdown()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
