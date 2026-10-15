package com.accessible.audioplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
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

    companion object {
        @Volatile
        var instance: VoiceService? = null
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var isActivated = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var settings: VoiceSettings
    private lateinit var contactCaller: ContactCaller
    private var toneGenerator: ToneGenerator? = null

    private var awaitingAnswer = false
    private var ttsSpeakingUntil = 0L
    private var ignoreUntil = 0L
    private var lastActivationTime = 0L

    private var recordingMode = false
    private var recordingFieldName: String = ""
    private var resultReceiver: ResultReceiver? = null

    private var choosingContact = false
    private var contactList: List<ContactCaller.Contact> = emptyList()
    private var contactIndex = 0

    private var awaitingName = false

    // РЕЖИМ МОНИТОРИНГА — слушаем только "завершить этот звонок"
    private var inCallMonitoring = false

    // Кэш имён контактов для словаря
    private var cachedNames: List<String> = emptyList()
    private var cacheTime = 0L

    // Таймауты
    private var silenceTimeout: Runnable? = null
    private var choosingTimeout: Runnable? = null
    private var confirmBeepRunnable: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())
    private val resetActivation = Runnable { isActivated = false }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.e("MYVOSK", "0. onCreate сервиса")
        settings = VoiceSettings(this)
        contactCaller = ContactCaller(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAssistant::WakeLock")
        wakeLock?.acquire()

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Не удалось создать ToneGenerator: ${e.message}")
        }

        tts = TextToSpeech(this, this)
        showNotification()
        initVoskSpeechEngine()
    }

    // ========== СИГНАЛЫ ==========
    private fun beepListen() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Log.e("MYVOSK", "Бип: слушаю")
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
    }

    private fun beepName() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }, 200)
            Log.e("MYVOSK", "Бип: имя")
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
    }

    private fun beepConfirm() {
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
            } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
        }
        handler.postDelayed(confirmBeepRunnable!!, 3000L)
    }

    private fun beepError() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 250)
            Log.e("MYVOSK", "Бип: ошибка")
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
    }

    private fun beepOk() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            Log.e("MYVOSK", "Бип: ок")
        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
    }

    // ========== ВХОДЯЩИЙ ЗВОНОК ==========
    fun onIncomingCall(callerName: String) {
        Log.e("MYVOSK", "onIncomingCall: $callerName")
        choosingContact = false
        awaitingName = false
        contactList = emptyList()
        contactIndex = 0
        cancelChoosingTimeout()
        cancelSilenceTimeout()

        awaitingAnswer = true
        speakOutShort("Звонит $callerName. Ответить?")
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
            } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
        }
        handler.postDelayed(confirmBeepRunnable!!, 2500L)
    }

    // Звонок активен (OFFHOOK) — включаем РЕЖИМ МОНИТОРИНГА
    fun onCallAnswered() {
        if (inCallMonitoring) {
            Log.e("MYVOSK", "Уже в мониторинге — игнорирую повтор OFFHOOK")
            return
        }
        Log.e("MYVOSK", "=== РЕЖИМ МОНИТОРИНГА ВКЛЮЧЁН ===")
        Log.e("MYVOSK", "Слушаю только 'завершить этот звонок'")
        awaitingAnswer = false
        choosingContact = false
        awaitingName = false
        cancelChoosingTimeout()
        cancelSilenceTimeout()
        inCallMonitoring = true
        ignoreUntil = System.currentTimeMillis() + 1500L
        startListeningForHangup()
    }

    // Звонок завершён (IDLE) — выключаем РЕЖИМ МОНИТОРИНГА
    fun onCallEnded() {
        if (!inCallMonitoring) {
            Log.e("MYVOSK", "Не в мониторинге — игнорирую повтор IDLE")
            return
        }
        Log.e("MYVOSK", "=== РЕЖИМ МОНИТОРИНГА ВЫКЛЮЧЕН ===")
        awaitingAnswer = false
        choosingContact = false
        awaitingName = false
        cancelChoosingTimeout()
        cancelSilenceTimeout()
        inCallMonitoring = false
        ignoreUntil = System.currentTimeMillis() + 2000L
        startListening()
        Log.e("MYVOSK", "Возврат в обычный режим ожидания")
    }

    // ========== TTS ==========
    private fun speakOutShort(text: String) {
        Log.e("MYVOSK", "speakOutShort: $text")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "call")
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
        val duration = (text.length * 150L + 2000L).coerceAtLeast(3000L)
        ttsSpeakingUntil = System.currentTimeMillis() + duration
        ignoreUntil = ttsSpeakingUntil + 1500L
    }

    private fun speakOut(text: String) {
        Log.e("MYVOSK", "speakOut: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        val duration = (text.length * 80L + 800L).coerceAtLeast(2000L)
        ttsSpeakingUntil = System.currentTimeMillis() + duration
        ignoreUntil = ttsSpeakingUntil + 2000L
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "RECORD_WORD" -> {
                val fieldName = intent.getStringExtra("FIELD") ?: ""
                resultReceiver = intent.getParcelableExtra("RECEIVER")
                startWordRecording(fieldName)
            }
        }
        return START_STICKY
    }

    // === ЗАПИСЬ СЛОВА В НАСТРОЙКАХ ===
    private fun startWordRecording(fieldName: String) {
        recordingFieldName = fieldName
        recordingMode = true
        speakOut("Говорите")
        handler.postDelayed({
            Thread { recordWordThread() }.start()
        }, 1500L)
    }

    private fun recordWordThread() {
        val currentModel = model ?: run { sendWordResult(""); return }
        var audioRecord: android.media.AudioRecord? = null
        var recognizer: Recognizer? = null
        try {
            val sampleRate = 16000
            val bufferSize = android.media.AudioRecord.getMinBufferSize(
                sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC, sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4
            )
            if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                sendWordResult(""); return
            }
            recognizer = Recognizer(currentModel, 16000.0f)
            audioRecord.startRecording()
            val buffer = ShortArray(bufferSize)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 5000L) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) recognizer.acceptWaveForm(buffer, read)
            }
            val text = extractText(recognizer.finalResult)
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
            try { resultReceiver?.send(0, bundle) } catch (_: Exception) {}
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("ru"))
    }

    private fun showNotification() {
        val channelId = "voice_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Голосовое управление", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Помощник активен")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun initVoskSpeechEngine() {
        Log.e("MYVOSK", "Загружаю модель")
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

    // === ГРАММАТИКА ОСНОВНАЯ ===
    private fun startListening() {
        try {
            cancelSilenceTimeout()
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
                    "да", "нет", "возьми", "сбрось", "отклони",
                    "взять", "поднять",
                    "позвони", "позвонить", "набери", "набрать",
                    "отмена", "отменить"
                ]"""

                val rec = Recognizer(it, 16000.0f, grammar)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "Слушание с грамматикой запущено")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListening: ${e.message}", e)
        }
    }

    // === РЕЖИМ МОНИТОРИНГА: только "завершить этот звонок" ===
    private fun startListeningForHangup() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            Log.e("MYVOSK", "Грамматика мониторинга: 'завершить этот звонок'")

            model?.let { m ->
                val grammar = """[
                    "завершить этот звонок",
                    "завершить звонок",
                    "положить трубку"
                ]"""
                val rec = Recognizer(m, 16000.0f, grammar)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "✅ Режим мониторинга активен")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListeningForHangup: ${e.message}", e)
        }
    }

    // === ГРАММАТИКА С ИМЕНАМИ КОНТАКТОВ ===
    private fun startListeningForName() {
        try {
            cancelSilenceTimeout()
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            Log.e("MYVOSK", "Грамматика остановлена, старт распознавания имён")

            model?.let { m ->
                val now = System.currentTimeMillis()
                if (now - cacheTime > 60000L || cachedNames.isEmpty()) {
                    cachedNames = contactCaller.getAllContactNames()
                    cacheTime = now
                }
                val allNames = cachedNames
                Log.e("MYVOSK", "Контактов в словаре: ${allNames.size}")

                val namesJson = if (allNames.isEmpty()) {
                    ""
                } else {
                    allNames.joinToString(",") { "\"$it\"" }
                }

                val grammar = if (namesJson.isEmpty()) {
                    null
                } else {
                    """[
                        $namesJson,
                        "отмена", "отменить", "стоп", "хватит",
                        "назад"
                    ]"""
                }

                val rec = if (grammar != null) {
                    Recognizer(m, 16000.0f, grammar)
                } else {
                    Recognizer(m, 16000.0f)
                }

                speechService = SpeechService(rec, 16000.0f)
                beepName()
                handler.postDelayed({
                    speechService?.startListening(this)
                    Log.e("MYVOSK", "Распознавание имён запущено (${allNames.size} имён)")
                    startSilenceTimeout()
                }, 300L)
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListeningForName: ${e.message}", e)
            startListening()
        }
    }

    private fun startSilenceTimeout() {
        cancelSilenceTimeout()
        silenceTimeout = Runnable {
            Log.e("MYVOSK", "Таймаут молчания (ожидание имени)")
            if (awaitingName) {
                speakOutShort("Не слышу. Назовите имя")
                handler.postDelayed({ startListeningForName() }, 3000L)
            }
        }
        handler.postDelayed(silenceTimeout!!, 8000L)
    }

    private fun cancelSilenceTimeout() {
        silenceTimeout?.let { handler.removeCallbacks(it) }
        silenceTimeout = null
    }

    // === ТАЙМАУТ ВЫБОРА КОНТАКТА ===
    private fun startChoosingTimeout() {
        cancelChoosingTimeout()
        choosingTimeout = Runnable {
            Log.e("MYVOSK", "Таймаут выбора контакта — сброс")
            choosingContact = false
            contactList = emptyList()
            contactIndex = 0
            beepError()
            speakOutShort("Отменено")
            startListening()
        }
        handler.postDelayed(choosingTimeout!!, 15000L)
    }

    private fun cancelChoosingTimeout() {
        choosingTimeout?.let { handler.removeCallbacks(it) }
        choosingTimeout = null
    }

    override fun onResult(hypothesis: String?) {
        if (recordingMode) return
        if (hypothesis == null) return
        val text = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"").trim().lowercase()
        if (text.isEmpty()) return

        Log.e("MYVOSK", "Распознано: '$text' (awaitingAnswer=$awaitingAnswer, choosingContact=$choosingContact, awaitingName=$awaitingName, inCall=$inCallMonitoring, ignore=${System.currentTimeMillis() < ignoreUntil})")

        // ========== ОБЩАЯ БЛОКИРОВКА ЭХА TTS ==========
        if (System.currentTimeMillis() < ignoreUntil) {
            Log.e("MYVOSK", "Игнорирую (эхо TTS): '$text'")
            return
        }

        // ========== РЕЖИМ МОНИТОРИНГА — только "завершить этот звонок" ==========
        if (inCallMonitoring) {
            val words = text.split(" ").toSet()
            val isHangupPhrase = (words.contains("завершить") && words.contains("звонок")) ||
                                 (words.contains("положить") && words.contains("трубку"))

            if (isHangupPhrase) {
                Log.e("MYVOSK", "✅ Команда завершения звонка: '$text'")
                beepOk()
                speakOutShort("Завершаю")
                handler.postDelayed({
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                            tm.endCall()
                            Log.e("MYVOSK", "Звонок завершён через TelecomManager")
                        }
                    } catch (e: Exception) {
                        Log.e("MYVOSK", "Ошибка завершения: ${e.message}", e)
                    }
                }, 1500L)
            } else {
                Log.e("MYVOSK", "В звонке, игнорирую: '$text'")
            }
            return
        }

        // ========== ЭКСТРЕННАЯ ОТМЕНА ==========
        if (text.contains("отмена") || text.contains("отменить") ||
            text.contains("стоп") || text.contains("хватит")) {
            Log.e("MYVOSK", "Экстренная отмена")
            choosingContact = false
            awaitingName = false
            awaitingAnswer = false
            contactList = emptyList()
            contactIndex = 0
            cancelSilenceTimeout()
            cancelChoosingTimeout()
            beepOk()
            speakOutShort("Отменено")
            return
        }

        // ========== ОЖИДАНИЕ ИМЕНИ ==========
        if (awaitingName) {
            cancelSilenceTimeout()
            Log.e("MYVOSK", "Распознавание имени: '$text'")

            val nameOnly = text.split(" ").firstOrNull()?.trim() ?: text

            // Фильтр команд — не принимать за имя
            val commandWords = listOf(
                "нет", "да", "отмена", "отменить", "позвони", "позвонить",
                "звонок", "трубку", "дата", "время", "день", "число",
                "завершить", "положить", "набери", "набрать"
            )
            if (nameOnly in commandWords) {
                Log.e("MYVOSK", "Это команда, а не имя: '$nameOnly' — переспрашиваю")
                beepError()
                speakOutShort("Назовите имя")
                handler.postDelayed({ startListeningForName() }, 3500L)
                return
            }

            if (nameOnly.length < 2) {
                beepError()
                speakOutShort("Не расслышал. Повторите")
                handler.postDelayed({ startListeningForName() }, 3500L)
                return
            }

            awaitingName = false
            beepOk()
            ignoreUntil = System.currentTimeMillis() + 500L
            handler.postDelayed({ startContactCall(nameOnly) }, 400)
            return
        }

        // ========== ВЫБОР КОНТАКТА ==========
        if (choosingContact) {
            cancelChoosingTimeout()

            val hasDa = text.contains("да") || text.contains("возьми") ||
                        text.contains("взять") || text.contains("поднять") || text == "д"
            val hasNet = text.contains("нет") || text.startsWith("не") ||
                         text.contains("сброс") || text.contains("отклон")

            if (hasNet && !hasDa) {
                contactIndex++
                if (contactIndex < contactList.size) {
                    val c = contactList[contactIndex]
                    speakOutShort("${ordinalWord(contactIndex + 1)} ${c.name}. Звонить?")
                    handler.postDelayed({ startListening() }, 100L)
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
                        } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
                    }
                    handler.postDelayed(confirmBeepRunnable!!, 3000L)
                    startChoosingTimeout()
                } else {
                    choosingContact = false
                    contactList = emptyList()
                    contactIndex = 0
                    awaitingName = true
                    beepError()
                    speakOutShort("Больше нет. Назовите другое имя")
                    handler.postDelayed({ startListeningForName() }, 6000L)
                }
                return
            }
            if (hasDa) {
                val c = contactList.getOrNull(contactIndex)
                if (c != null) {
                    beepOk()
                    speakOutShort("Звоню")
                    choosingContact = false
                    contactList = emptyList()
                    contactIndex = 0
                    ignoreUntil = System.currentTimeMillis() + 10000L
                    handler.postDelayed({
                        contactCaller.callNumber(c.number)
                    }, 1500L)
                } else {
                    choosingContact = false
                    contactList = emptyList()
                    contactIndex = 0
                }
                return
            }
            startChoosingTimeout()
            return
        }

        // ========== ВХОДЯЩИЙ ЗВОНОК ==========
        if (awaitingAnswer) {
            val hasDa = text.contains("да") || text.contains("возьми") ||
                        text.contains("взять") || text.contains("поднять") || text == "д"
            val hasNet = text.contains("нет") || text.startsWith("не") ||
                         text.contains("сброс") || text.contains("отклон")
            if (hasNet && !hasDa) {
                beepOk()
                speakOutShort("Отклоняю"); rejectCall(); awaitingAnswer = false; return
            }
            if (hasDa) {
                beepOk()
                speakOutShort("Отвечаю"); acceptCall(); awaitingAnswer = false; return
            }
            return
        }

        val activation = settings.activationWord
        val shutdownWord = settings.shutdownWord

        if (text.contains(shutdownWord)) {
            speakOut("Выключаюсь")
            handler.postDelayed({ stopSelf(); System.exit(0) }, 1500)
            return
        }

        // ========== АКТИВАЦИЯ ==========
        if (!isActivated) {
            if (text.contains(activation)) {
                if (System.currentTimeMillis() - lastActivationTime < 3000) return
                lastActivationTime = System.currentTimeMillis()
                isActivated = true
                val cmd = text.replace(activation, "").trim()
                if (cmd.isNotEmpty()) {
                    executeCommand(cmd); isActivated = false
                } else {
                    beepListen()
                    handler.postDelayed({ speakOut(settings.confirmWord) }, 200)
                    handler.removeCallbacks(resetActivation)
                    handler.postDelayed(resetActivation, 15000L)
                }
            }
        } else {
            if (text == activation) return
            if (text.length < 3) return
            executeCommand(text)
            handler.removeCallbacks(resetActivation)
            isActivated = false
        }
    }

    // ========== КОМАНДЫ ==========
    private fun executeCommand(command: String) {
        Log.e("MYVOSK", "executeCommand: '$command'")
        val calendar = Calendar.getInstance()

        // === ЗВОНОК ===
        if (command.contains("позвони") || command.contains("позвонить") ||
            command.contains("набери") || command.contains("набрать")) {

            val name = command
                .replace("позвонить", "").replace("позвони", "")
                .replace("набрать", "").replace("набери", "").trim()

            Log.e("MYVOSK", "Имя в команде: '$name'")

            if (name.isEmpty() || name.length < 3 ||
                name == "день" || name == "дата" || name == "время" ||
                name == "часы" || name == "час") {
                awaitingName = true
                speakOut("Кому позвонить?")
                handler.postDelayed({ startListeningForName() }, 3000L)
                return
            }
            startContactCall(name)
            return
        }

        // === ВРЕМЯ ===
        if (command.contains("время") || command.contains("час") || command.contains("сколько")) {
            val f = SimpleDateFormat("HH:mm", Locale("ru"))
            speakOut("Сейчас ${f.format(calendar.time)}")
            return
        }

        // === ДАТА ===
        if (command.contains("день") || command.contains("дата") || command.contains("число")) {
            val f = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
            speakOut("Сегодня ${f.format(calendar.time)}")
            return
        }

        beepError()
        speakOut("Не понял")
    }

    private fun startContactCall(query: String) {
        Log.e("MYVOSK", "startContactCall: '$query'")
        val contacts = contactCaller.findContacts(query)

        if (contacts.isEmpty()) {
            beepError()
            speakOut("Контакт не найден. Назовите другое имя")
            awaitingName = true
            handler.postDelayed({ startListeningForName() }, 6000L)
            return
        }
        if (contacts.size == 1) {
            val c = contacts[0]
            choosingContact = true
            contactList = contacts
            contactIndex = 0
            speakOutShort("${c.name}. Звонить?")
            handler.postDelayed({ startListening() }, 100L)
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
                } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
            }
            handler.postDelayed(confirmBeepRunnable!!, 3000L)
            startChoosingTimeout()
            return
        }
        contactList = contacts
        contactIndex = 0
        choosingContact = true
        val first = contacts[0]
        val ordinal = ordinalWord(1)
        speakOutShort("Нашёл ${contacts.size}. $ordinal ${first.name}. Звонить?")
        handler.postDelayed({ startListening() }, 100L)
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
            } catch (e: Exception) { Log.e("MYVOSK", "Ошибка бипа: ${e.message}") }
        }
        handler.postDelayed(confirmBeepRunnable!!, 3500L)
        startChoosingTimeout()
    }

    private fun ordinalWord(n: Int): String {
        return when (n) {
            1 -> "Первый"; 2 -> "Второй"; 3 -> "Третий"
            4 -> "Четвёртый"; 5 -> "Пятый"; 6 -> "Шестой"
            7 -> "Седьмой"; 8 -> "Восьмой"; 9 -> "Девятый"
            10 -> "Десятый"; else -> "$n-й"
        }
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { Log.e("MYVOSK", "onError: ${exception?.message}", exception) }
    override fun onTimeout() {}
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(resetActivation)
        cancelSilenceTimeout()
        cancelChoosingTimeout()
        confirmBeepRunnable?.let { handler.removeCallbacks(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        speechService?.stop(); speechService?.shutdown()
        tts?.stop(); tts?.shutdown()
        try { toneGenerator?.release() } catch (_: Exception) {}
        toneGenerator = null
        super.onDestroy()
    }
}
