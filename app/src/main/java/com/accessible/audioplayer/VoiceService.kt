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

    // Режим ожидания имени (после "позвони")
    private var awaitingName = false

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

        // Генератор короткого сигнала (бип)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Не удалось создать ToneGenerator: ${e.message}")
        }

        tts = TextToSpeech(this, this)
        showNotification()
        initVoskSpeechEngine()
    }

    private fun beep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Log.e("MYVOSK", "Бип")
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка бипа: ${e.message}")
        }
    }

    fun onIncomingCall(callerName: String) {
        Log.e("MYVOSK", "onIncomingCall: $callerName")
        awaitingAnswer = true
        speakOutShort("Звонит $callerName. Ответить на вызов?")
    }

    fun onCallAnswered() {
        awaitingAnswer = false
        choosingContact = false
        awaitingName = false
    }

    fun onCallEnded() {
        awaitingAnswer = false
        choosingContact = false
        awaitingName = false
    }

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
        val duration = (text.length * 60L + 500L).coerceAtLeast(1200L)
        ttsSpeakingUntil = System.currentTimeMillis() + duration
        ignoreUntil = ttsSpeakingUntil + 500L
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

    // === ГРАММАТИКА ===
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

    // === СВОБОДНЫЙ РЕЖИМ ДЛЯ ИМЕНИ ===
    private fun startListeningForName() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            Log.e("MYVOSK", "Грамматика остановлена, старт свободного распознавания")

            model?.let {
                val rec = Recognizer(it, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "Свободный распознаватель запущен на имя")
                beep()
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListeningForName: ${e.message}", e)
            startListening()
        }
    }

    override fun onResult(hypothesis: String?) {
        if (recordingMode) return
        if (hypothesis == null) return
        val text = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"").trim().lowercase()
        if (text.isEmpty()) return

        Log.e("MYVOSK", "Распознано: '$text' (awaitingAnswer=$awaitingAnswer, choosingContact=$choosingContact, awaitingName=$awaitingName)")

        // ========== ОЖИДАНИЕ ИМЕНИ (свободный режим) ==========
        if (awaitingName) {
            // Блокировка эха TTS
            if (System.currentTimeMillis() < ignoreUntil) {
                Log.e("MYVOSK", "Игнорирую эхо в awaitingName: '$text'")
                return
            }

            Log.e("MYVOSK", "Свободный режим: услышано '$text'")

            val isCommandWord = text.contains("позвони") || text.contains("позвонить") ||
                    text.contains("набери") || text.contains("набрать") ||
                    text.contains("отмена") || text.contains("отменить") ||
                    text.contains("стоп") || text.contains("хватит") ||
                    text.contains("слушай") || text.contains("выключись") ||
                    text.contains("кому")

            if (isCommandWord) {
                Log.e("MYVOSK", "Это команда, а не имя — переспрашиваю")
                speakOutShort("Назовите имя")
                handler.postDelayed({ startListeningForName() }, 3000L)
                return
            }

            if (text.length < 2) {
                speakOutShort("Не расслышал. Повторите")
                handler.postDelayed({ startListeningForName() }, 3000L)
                return
            }

            awaitingName = false
            handler.postDelayed({ startListening() }, 500)
            handler.postDelayed({ startContactCall(text) }, 200)
            return
        }

        // ========== ВЫБОР КОНТАКТА ==========
        if (choosingContact) {
            val hasDa = text.contains("да") || text.contains("возьми") ||
                        text.contains("взять") || text.contains("поднять") || text == "д"
            val hasNet = text.contains("нет") || text.startsWith("не") ||
                         text.contains("сброс") || text.contains("отклон")
            val hasCancel = text.contains("отмена") || text.contains("отменить") ||
                            text.contains("стоп") || text.contains("хватит")

            if (hasCancel) {
                speakOutShort("Отменяю")
                choosingContact = false
                contactList = emptyList()
                contactIndex = 0
                return
            }
            if (hasNet && !hasDa) {
                contactIndex++
                if (contactIndex < contactList.size) {
                    val c = contactList[contactIndex]
                    speakOutShort("${ordinalWord(contactIndex + 1)} ${c.name}. Звонить?")
                } else {
                    speakOutShort("Больше нет")
                    choosingContact = false
                    contactList = emptyList()
                    contactIndex = 0
                }
                return
            }
            if (hasDa) {
                val c = contactList.getOrNull(contactIndex)
                if (c != null) {
                    speakOutShort("Звоню ${c.name}")
                    contactCaller.callNumber(c.number)
                }
                choosingContact = false
                contactList = emptyList()
                contactIndex = 0
                return
            }
            return
        }

        // ========== ВХОДЯЩИЙ ЗВОНОК ==========
        if (awaitingAnswer) {
            val hasDa = text.contains("да") || text.contains("возьми") ||
                        text.contains("взять") || text.contains("поднять") || text == "д"
            val hasNet = text.contains("нет") || text.startsWith("не") ||
                         text.contains("сброс") || text.contains("отклон")
            if (hasNet && !hasDa) {
                speakOutShort("Отклоняю"); rejectCall(); awaitingAnswer = false; return
            }
            if (hasDa) {
                speakOutShort("Отвечаю"); acceptCall(); awaitingAnswer = false; return
            }
            return
        }

        // ========== БЛОКИРОВКА ЭХА ==========
        if (System.currentTimeMillis() < ignoreUntil) {
            Log.e("MYVOSK", "Игнорирую (эхо): '$text'")
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
                    speakOut(settings.confirmWord)
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
                handler.postDelayed({ startListeningForName() }, 3500L)
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

        // === ОТМЕНА ===
        if (command.contains("отмена") || command.contains("отменить")) {
            choosingContact = false
            awaitingName = false
            contactList = emptyList()
            contactIndex = 0
            speakOut("Отменено")
            return
        }

        speakOut("Не понял")
    }

    private fun startContactCall(query: String) {
        Log.e("MYVOSK", "startContactCall: '$query'")
        val contacts = contactCaller.findContacts(query)

        if (contacts.isEmpty()) {
            speakOut("Контакт не найден")
            return
        }
        if (contacts.size == 1) {
            val c = contacts[0]
            speakOut("Звоню ${c.name}")
            contactCaller.callNumber(c.number)
            return
        }
        contactList = contacts
        contactIndex = 0
        choosingContact = true
        val countWord = countWord(contacts.size)
        val first = contacts[0]
        val ordinal = ordinalWord(1)
        speakOut("Нашёл $countWord контакта. $ordinal ${first.name}. Звонить?")
    }

    private fun countWord(n: Int): String {
        return "$n"
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
        wakeLock?.let { if (it.isHeld) it.release() }
        speechService?.stop(); speechService?.shutdown()
        tts?.stop(); tts?.shutdown()
        try { toneGenerator?.release() } catch (_: Exception) {}
        toneGenerator = null
        super.onDestroy()
    }
}
