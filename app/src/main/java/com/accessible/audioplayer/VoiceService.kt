package com.accessible.audioplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

    // Режим записи слова
    private var recordingMode = false
    private var recordingFieldName: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val resetActivation = Runnable {
        Log.e("MYVOSK", "Таймаут — сбрасываю активацию")
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
                Log.e("MYVOSK", "Получена команда записи для поля: $fieldName")
                startWordRecording(fieldName)
            }
        }
        return START_STICKY
    }

    // === Запись слова через уже загруженную модель ===
    private fun startWordRecording(fieldName: String) {
        recordingFieldName = fieldName
        recordingMode = true
        speakOut("Говорите")
        Log.e("MYVOSK", "Начинаю запись слова для поля: $fieldName")

        // Запускаем поток записи через 1.5 секунды (после того как TTS скажет "Говорите")
        handler.postDelayed({
            Thread {
                recordWordThread()
            }.start()
        }, 1500L)
    }

    private fun recordWordThread() {
        val currentModel = model
        if (currentModel == null) {
            Log.e("MYVOSK", "Модель не загружена, отмена")
            sendWordResult("")
            return
        }

        var audioRecord: AudioRecord? = null
        var recognizer: Recognizer? = null

        try {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MYVOSK", "AudioRecord не инициализирован")
                sendWordResult("")
                return
            }

            recognizer = Recognizer(currentModel, 16000.0f)
            audioRecord.startRecording()
            Log.e("MYVOSK", "Запись 5 секунд")

            val buffer = ShortArray(bufferSize)
            val startTime = System.currentTimeMillis()
            val durationMs = 5000L

            while (System.currentTimeMillis() - startTime < durationMs) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    recognizer.acceptWaveForm(buffer, read)
                }
            }

            val resultJson = recognizer.finalResult
            Log.e("MYVOSK", "Vosk результат: $resultJson")

            val text = extractText(resultJson)
            if (text.isNotEmpty()) {
                Log.e("MYVOSK", "Распознано слово: '$text'")
                sendWordResult(text)
            } else {
                sendWordResult("")
            }
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
            val intent = Intent("VOICE_WORD_RECORDED")
            intent.putExtra("FIELD", recordingFieldName)
            intent.putExtra("WORD", text)
            sendBroadcast(intent)
            Log.e("MYVOSK", "Отправлен Broadcast: поле=$recordingFieldName, слово='$text'")
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
        } catch (e: Exception) {
            ""
        }
    }

    // === Обработка входящего звонка ===
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
                val rec = Recognizer(it, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
            }
        } catch (e: IOException) {
            Log.e("MYVOSK", "Ошибка startListening: ${e.message}", e)
        }
    }

    override fun onResult(hypothesis: String?) {
        // Игнорируем всё во время записи слова
        if (recordingMode) {
            Log.e("MYVOSK", "Игнорирую onResult (идёт запись слова)")
            return
        }

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

        if (text.contains(shutdownWord)) {
            speakOut("Выключаюсь")
            handler.postDelayed({
                stopSelf()
                System.exit(0)
            }, 1500)
            return
        }

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
        val calendar = Calendar.getInstance()
        when {
            command.contains("время") || command.contains("сколько времени") -> {
                val timeFormat = SimpleDateFormat("HH:mm", Locale("ru"))
                speakOut("Сейчас ${timeFormat.format(calendar.time)}")
            }
            command.contains("день") || command.contains("дата") -> {
                val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
                speakOut("Сегодня ${dateFormat.format(calendar.time)}")
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
        speechService?.stop()
        speechService?.shutdown()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
