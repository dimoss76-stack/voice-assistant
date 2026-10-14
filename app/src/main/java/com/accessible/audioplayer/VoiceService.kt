package com.accessible.audioplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.ResultReceiver
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
    private var ignoreUntil = 0L
    private var lastActivationTime = 0L

    private var recordingMode = false
    private var recordingFieldName: String = ""
    private var resultReceiver: ResultReceiver? = null

    private val handler = Handler(Looper.getMainLooper())
    private val resetActivation = Runnable {
        isActivated = false
    }

    override fun onCreate() {
        super.onCreate()
        Log.e("MYVOSK", "0. onCreate сервиса")
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
            "CALL_ANSWERED" -> awaitingAnswer = false
            "CALL_ENDED" -> awaitingAnswer = false
            "RECORD_WORD" -> {
                val fieldName = intent.getStringExtra("FIELD") ?: ""
                resultReceiver = intent.getParcelableExtra("RECEIVER")
                Log.e("MYVOSK", "Получена команда записи для поля: $fieldName")
                startWordRecording(fieldName)
            }
        }
        return START_STICKY
    }

    private fun startWordRecording(fieldName: String) {
        recordingFieldName = fieldName
        recordingMode = true
        speakOut("Говорите")
        Log.e("MYVOSK", "Начинаю запись слова для поля: $fieldName")
        handler.postDelayed({
            Thread { recordWordThread() }.start()
        }, 1500L)
    }

    private fun recordWordThread() {
        val currentModel = model
        if (currentModel == null) {
            Log.e("MYVOSK", "Модель не загружена")
            sendWordResult("")
            return
        }

        var audioRecord: android.media.AudioRecord? = null
        var recognizer: Recognizer? = null

        try {
            val sampleRate = 16000
            val bufferSize = android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4
            )

            if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e("MYVOSK", "AudioRecord не инициализирован")
                sendWordResult("")
                return
            }

            recognizer = Recognizer(currentModel, 16000.0f)
            audioRecord.startRecording()
            Log.e("MYVOSK", "Запись 5 секунд")

            val buffer = ShortArray(bufferSize)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 5000L) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) recognizer.acceptWaveForm(buffer, read)
            }

            val resultJson = recognizer.finalResult
            Log.e("MYVOSK", "Vosk результат: $resultJson")
            val text = extractText(resultJson)
            Log.e("MYVOSK", "Распознано слово: '$text'")
            sendWordResult(text)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка записи: ${e.message}", e)
            sendWordResult("")
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            try { recognizer?.close() } catch (_: Exception) {}
        }
    }

    private fun sendWordResult(text: String) {
        handler.post {
            val bundle = Bundle().apply {
                putString("FIELD", recordingFieldName)
                putString("WORD", text)
            }
            try {
                resultReceiver?.send(0, bundle)
                Log.e("MYVOSK", "ResultReceiver.send: поле=$recordingFieldName, слово='$text'")
            } catch (e: Exception) {
                Log.e("MYVOSK", "Ошибка send: ${e.message}", e)
            }
            recordingMode = false
            recordingFieldName = ""
        }
    }

    private fun extractText(json: String): String {
        return try {
            val start = json.indexOf("\"text\"")
            if (start < 0) return ""
            val afterColon = json.indexOf(":", start) + 1
            val firstQuote = json.indexOf("\"", afterColon) + 1
            val secondQuote = json.indexOf("\"", firstQuote)
            if (firstQuote <= 0 || secondQuote <= 0) return ""
            json.substring(firstQuote, secondQuote).trim()
        } catch (e: Exception) { "" }
    }

    private fun handleIncomingCall(callerName: String) {
        awaitingAnswer = true
        speakOut("Звонит $callerName. Ответить?")
    }

    private fun acceptCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                tm.acceptRingingCall()
            }
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка приёма: ${e.message}", e) }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                tm.endCall()
            }
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка отклонения: ${e.message}", e) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("ru"))
    }

    private fun speakOut(text: String) {
        Log.e("MYVOSK", "speakOut: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        val duration = (text.length * 100L + 3000L).coerceAtLeast(5000L)
        ttsSpeakingUntil = System.currentTimeMillis() + duration
        ignoreUntil = ttsSpeakingUntil + 2000L
    }

    private fun showNotification() {
        val channelId = "voice_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Голосовое управление", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Помощник активен")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
           startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
         } else {
          startForeground(1, notification)
}
    }

    private fun initVoskSpeechEngine() {
        // Всегда стандартная модель (streaming не тянет телефон)
        loadSmallModel()
    }

    private fun loadSmallModel() {
        Log.e("MYVOSK", "Загружаю стандартную модель")
        try {
            StorageService.unpack(this, "model-ru", "model",
                { m: Model ->
                    this.model = m
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
                val activation = settings.activationWord
                val confirm = settings.confirmWord
                val shutdownWord = settings.shutdownWord

                val grammar = """[
                    "$activation",
                    "$confirm",
                    "$shutdownWord",
                    "время", "часы", "час", "сколько",
                    "дата", "день", "число",
                    "да", "нет", "ответь", "ответить", "возьми", "сбрось", "отклони",
                    "[unk]"
                ]"""

                Log.e("MYVOSK", "Грамматика: $grammar")

                val rec = Recognizer(it, 16000.0f, grammar)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListening: ${e.message}", e)
            try {
                model?.let {
                    val rec = Recognizer(it, 16000.0f)
                    speechService = SpeechService(rec, 16000.0f)
                    speechService?.startListening(this)
                    Log.e("MYVOSK", "Fallback: без грамматики")
                }
            } catch (e2: Exception) {
                Log.e("MYVOSK", "Fallback тоже не сработал: ${e2.message}", e2)
            }
        }
    }

    override fun onResult(hypothesis: String?) {
        if (recordingMode) return
        if (hypothesis == null) return
        val text = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"").trim().lowercase()
        if (text.isEmpty()) return

        Log.e("MYVOSK", "Распознано: '$text'")

        // Блокировка эха TTS
        if (System.currentTimeMillis() < ignoreUntil) {
            Log.e("MYVOSK", "Игнорирую (эхо TTS): '$text'")
            return
        }

        val activation = settings.activationWord
        val shutdownWord = settings.shutdownWord

        // Голосовое выключение — без активации
        if (text.contains(shutdownWord)) {
            speakOut("Выключаюсь")
            handler.postDelayed({ stopSelf(); System.exit(0) }, 1500)
            return
        }

        // === ОБРАБОТКА ЗВОНКА ===
        if (awaitingAnswer) {
            // Убираем блокировку — слушаем сразу
            ttsSpeakingUntil = 0L
            ignoreUntil = 0L

            Log.e("MYVOSK", "Во время звонка услышано: '$text'")

            val lower = text.lowercase()
            val hasNet = lower.contains("нет") || lower.contains("не ") || lower.startsWith("не")
            val hasDa = lower.contains("да") || lower.contains("ответ") || lower.contains("возьми")

            if (hasNet && !hasDa) {
                Log.e("MYVOSK", "Слышу НЕТ — отклоняю")
                speakOut("Отклоняю")
                rejectCall()
                awaitingAnswer = false
                return
            }
            if (hasDa) {
                Log.e("MYVOSK", "Слышу ДА — принимаю звонок")
                speakOut("Отвечаю")
                acceptCall()
                awaitingAnswer = false
                return
            }

            Log.e("MYVOSK", "Не команда во время звонка: '$text'")
            return
        }

        // === АКТИВАЦИЯ ===
        if (!isActivated) {
            if (text.contains(activation)) {
                if (System.currentTimeMillis() - lastActivationTime < 3000) return
                lastActivationTime = System.currentTimeMillis()
                isActivated = true
                val cmd = text.replace(activation, "").trim()
                if (cmd.isNotEmpty()) {
                    executeCommand(cmd); isActivated = false
                } else {
                    speakOut(settings.confirmWord)
                    handler.removeCallbacks(resetActivation)
                    handler.postDelayed(resetActivation, 15000L)
                }
            }
        } else {
            if (text == activation) return
            if (text.length < 3) {
                Log.e("MYVOSK", "Слишком короткое, игнорирую: '$text'")
                return
            }
            executeCommand(text)
            handler.removeCallbacks(resetActivation)
            isActivated = false
        }
    }

    private fun executeCommand(command: String) {
        val calendar = Calendar.getInstance()
        when {
            command.contains("время") || command.contains("час") || command.contains("сколько") -> {
                val f = SimpleDateFormat("HH:mm", Locale("ru"))
                speakOut("Сейчас ${f.format(calendar.time)}")
            }
            command.contains("день") || command.contains("дата") || command.contains("число") -> {
                val f = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
                speakOut("Сегодня ${f.format(calendar.time)}")
            }
            else -> speakOut("Не понял")
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
        speechService?.stop(); speechService?.shutdown()
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}
