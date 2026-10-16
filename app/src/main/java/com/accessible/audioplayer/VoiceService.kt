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
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class VoiceService : Service() {

    companion object {
        @Volatile
        var instance: VoiceService? = null
    }

    private lateinit var settings: VoiceSettings
    private lateinit var contactCaller: ContactCaller
    private lateinit var tts: TtsSpeaker
    private lateinit var vosk: VoskEngine

    private var isActivated = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastActivationTime = 0L

    private var recordingMode = false
    private var recordingFieldName: String = ""
    private var resultReceiver: ResultReceiver? = null

    private var awaitingAnswer = false
    private var choosingContact = false
    private var contactList: List<ContactCaller.Contact> = emptyList()
    private var contactIndex = 0
    private var awaitingName = false
    private var inCallMonitoring = false

    private var cachedNames: List<String> = emptyList()
    private var cacheTime = 0L

    private var silenceTimeout: Runnable? = null
    private var choosingTimeout: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())
    private val resetActivation = Runnable { isActivated = false }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.e("MYVOSK", "0. onCreate сервиса")
        settings = VoiceSettings(this)
        contactCaller = ContactCaller(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceAssistant::WakeLock")
        wakeLock?.acquire()

        tts = TtsSpeaker(this)
        tts.init()

        vosk = VoskEngine(
            context = this,
            onResultCallback = { text -> handleRecognizedText(text) },
            onErrorCallback = { e -> Log.e("MYVOSK", "Vosk error: ${e.message}") }
        )
        vosk.onModelReady = {
            startListening()
            tts.speakOut("Помощник готов")
        }
        vosk.init()

        showNotification()
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

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(resetActivation)
        cancelSilenceTimeout()
        cancelChoosingTimeout()
        tts.cancelConfirmBeep()
        wakeLock?.let { if (it.isHeld) it.release() }
        vosk.release()
        tts.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== СИГНАЛЫ ОТ CallReceiver ====================
    fun onIncomingCall(callerName: String) {
        Log.e("MYVOSK", "onIncomingCall: $callerName")
        choosingContact = false
        awaitingName = false
        contactList = emptyList()
        contactIndex = 0
        cancelChoosingTimeout()
        cancelSilenceTimeout()

        awaitingAnswer = true
        tts.speakOutShort("Звонит $callerName. Ответить?")
        tts.scheduleConfirmBeep()
    }

    fun onCallAnswered() {
        if (inCallMonitoring) {
            Log.e("MYVOSK", "Уже в мониторинге — игнорирую повтор OFFHOOK")
            return
        }
        Log.e("MYVOSK", "=== РЕЖИМ МОНИТОРИНГА ВКЛЮЧЁН ===")
        awaitingAnswer = false
        choosingContact = false
        awaitingName = false
        cancelChoosingTimeout()
        cancelSilenceTimeout()
        tts.cancelConfirmBeep()
        inCallMonitoring = true
        tts.setIgnoreUntil(System.currentTimeMillis() + 1500L)
        vosk.startListeningForHangup()
    }

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
        tts.setIgnoreUntil(System.currentTimeMillis() + 2000L)
        startListening()
    }

    // ==================== ЗАПУСК РАСПОЗНАВАНИЯ ====================
    private fun startListening() {
        cancelSilenceTimeout()
        vosk.startListening(
            activation = settings.activationWord,
            confirm = settings.confirmWord,
            shutdown = settings.shutdownWord
        )
    }

    private fun startListeningForName() {
        cancelSilenceTimeout()
        val now = System.currentTimeMillis()
        if (now - cacheTime > 60000L || cachedNames.isEmpty()) {
            cachedNames = contactCaller.getAllContactNames()
            cacheTime = now
        }
        Log.e("MYVOSK", "Контактов в словаре: ${cachedNames.size}")

        vosk.prepareListeningForNames(cachedNames)
        tts.beepName()
        tts.setIgnoreUntil(System.currentTimeMillis() + 800L)
        handler.postDelayed({
            vosk.startListeningNow()
            Log.e("MYVOSK", "Распознавание имён запущено (${cachedNames.size} имён)")
            startSilenceTimeout()
        }, 1000L)
    }

    // ==================== ТАЙМАУТЫ ====================
    private fun startSilenceTimeout() {
        cancelSilenceTimeout()
        silenceTimeout = Runnable {
            Log.e("MYVOSK", "Таймаут молчания (ожидание имени)")
            if (awaitingName) {
                tts.speakOutShort("Не слышу. Назовите имя")
                handler.postDelayed({ startListeningForName() }, 3000L)
            }
        }
        handler.postDelayed(silenceTimeout!!, 10000L)
    }

    private fun cancelSilenceTimeout() {
        silenceTimeout?.let { handler.removeCallbacks(it) }
        silenceTimeout = null
    }

    private fun startChoosingTimeout() {
        cancelChoosingTimeout()
        choosingTimeout = Runnable {
            Log.e("MYVOSK", "Таймаут выбора контакта — сброс")
            choosingContact = false
            contactList = emptyList()
            contactIndex = 0
            tts.beepError()
            tts.speakOutShort("Отменено")
            startListening()
        }
        handler.postDelayed(choosingTimeout!!, 15000L)
    }

    private fun cancelChoosingTimeout() {
        choosingTimeout?.let { handler.removeCallbacks(it) }
        choosingTimeout = null
    }

    private fun isAnyActiveMode(): Boolean {
        return choosingContact || awaitingName || awaitingAnswer || inCallMonitoring || isActivated
    }

    // ==================== ОБРАБОТКА РАСПОЗНАННОГО ТЕКСТА ====================
    private fun handleRecognizedText(text: String) {
        Log.e("MYVOSK", "Распознано: '$text' (awaitingAnswer=$awaitingAnswer, choosingContact=$choosingContact, awaitingName=$awaitingName, inCall=$inCallMonitoring, ignore=${System.currentTimeMillis() < tts.ignoreUntil})")

        if (recordingMode) return

        if (System.currentTimeMillis() < tts.ignoreUntil) {
            Log.e("MYVOSK", "Игнорирую (эхо TTS): '$text'")
            return
        }

        // РЕЖИМ МОНИТОРИНГА
        if (inCallMonitoring) {
            val words = text.split(" ").toSet()
            val isHangupPhrase = (words.contains("завершить") && words.contains("звонок")) ||
                                 (words.contains("положить") && words.contains("трубку"))

            if (isHangupPhrase) {
                Log.e("MYVOSK", "✅ Команда завершения звонка: '$text'")
                tts.beepOk()
                tts.speakOutShort("Завершаю")
                handler.postDelayed({
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                            tm.endCall()
                            Log.e("MYVOSK", "Звонок завершён")
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

        // ЭКСТРЕННАЯ ОТМЕНА (Вариант В)
        if (isAnyActiveMode()) {
            if (text.contains("отмена") || text.contains("отменить") ||
                text.contains("стоп") || text.contains("хватит")) {
                Log.e("MYVOSK", "Экстренная отмена (активный режим)")
                choosingContact = false
                awaitingName = false
                awaitingAnswer = false
                isActivated = false
                contactList = emptyList()
                contactIndex = 0
                cancelSilenceTimeout()
                cancelChoosingTimeout()
                tts.beepOk()
                tts.speakOutShort("Отменено")
                return
            }
        } else {
            Log.e("MYVOSK", "Отмена вне активного режима — игнорирую")
        }

        // ОЖИДАНИЕ ИМЕНИ
        if (awaitingName) {
            cancelSilenceTimeout()
            Log.e("MYVOSK", "Распознавание имени: '$text'")

            val nameOnly = text.split(" ").firstOrNull()?.trim() ?: text
            val commandWords = listOf(
                "нет", "да", "отмена", "отменить", "позвони", "позвонить",
                "звонок", "трубку", "дата", "время", "день", "число",
                "завершить", "положить", "набери", "набрать"
            )
            if (nameOnly in commandWords) {
                Log.e("MYVOSK", "Это команда, а не имя: '$nameOnly'")
                tts.beepError()
                tts.speakOutShort("Назовите имя")
                handler.postDelayed({ startListeningForName() }, 3500L)
                return
            }

            if (nameOnly.length < 2) {
                tts.beepError()
                tts.speakOutShort("Не расслышал. Повторите")
                handler.postDelayed({ startListeningForName() }, 3500L)
                return
            }

            awaitingName = false
            tts.beepOk()
            tts.setIgnoreUntil(System.currentTimeMillis() + 500L)
            handler.postDelayed({ startContactCall(nameOnly) }, 400)
            return
        }

        // ВЫБОР КОНТАКТА
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
                    tts.speakOutShort("${ordinalWord(contactIndex + 1)} ${c.name}. Звонить?")
                    handler.postDelayed({ startListening() }, 100L)
                    tts.scheduleConfirmBeep()
                    startChoosingTimeout()
                } else {
                    choosingContact = false
                    contactList = emptyList()
                    contactIndex = 0
                    awaitingName = true
                    tts.beepError()
                    tts.speakOutShort("Больше нет. Назовите другое имя")
                    handler.postDelayed({ startListeningForName() }, 6000L)
                }
                return
            }
            if (hasDa) {
                val c = contactList.getOrNull(contactIndex)
                if (c != null) {
                    tts.beepOk()
                    tts.speakOutShort("Звоню")
                    choosingContact = false
                    contactList = emptyList()
                    contactIndex = 0
                    tts.setIgnoreUntil(System.currentTimeMillis() + 10000L)
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

        // ВХОДЯЩИЙ ЗВОНОК
        if (awaitingAnswer) {
            val hasDa = text.contains("да") || text.contains("возьми") ||
                        text.contains("взять") || text.contains("поднять") || text == "д"
            val hasNet = text.contains("нет") || text.startsWith("не") ||
                         text.contains("сброс") || text.contains("отклон")
            if (hasNet && !hasDa) {
                tts.beepOk()
                tts.speakOutShort("Отклоняю")
                rejectCall()
                awaitingAnswer = false
                return
            }
            if (hasDa) {
                tts.beepOk()
                tts.speakOutShort("Отвечаю")
                acceptCall()
                awaitingAnswer = false
                return
            }
            return
        }

        val activation = settings.activationWord
        val shutdownWord = settings.shutdownWord

        if (text.contains(shutdownWord)) {
            tts.speakOut("Выключаюсь")
            handler.postDelayed({ stopSelf(); System.exit(0) }, 1500)
            return
        }

        // АКТИВАЦИЯ
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
                    tts.beepListen()
                    handler.postDelayed({ tts.speakOut(settings.confirmWord) }, 200)
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

    // ==================== КОМАНДЫ ====================
    private fun executeCommand(command: String) {
        Log.e("MYVOSK", "executeCommand: '$command'")
        val calendar = Calendar.getInstance()

        // ЗВОНОК
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
                tts.speakOut("Каму звонить?")
                handler.postDelayed({ startListeningForName() }, 3000L)
                return
            }
            startContactCall(name)
            return
        }

        // ВРЕМЯ
        if (command.contains("время") || command.contains("час") || command.contains("сколько")) {
            val f = SimpleDateFormat("HH:mm", Locale("ru"))
            tts.speakOut("Сейчас ${f.format(calendar.time)}")
            return
        }

        // ДАТА
        if (command.contains("день") || command.contains("дата") || command.contains("число")) {
            val f = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
            tts.speakOut("Сегодня ${f.format(calendar.time)}")
            return
        }

        tts.beepError()
        tts.speakOut("Не понял")
    }

    private fun startContactCall(query: String) {
        Log.e("MYVOSK", "startContactCall: '$query'")
        val contacts = contactCaller.findContacts(query)

        if (contacts.isEmpty()) {
            tts.beepError()
            tts.speakOut("Контакт не найден. Назовите другое имя")
            awaitingName = true
            handler.postDelayed({ startListeningForName() }, 6000L)
            return
        }
        if (contacts.size == 1) {
            val c = contacts[0]
            choosingContact = true
            contactList = contacts
            contactIndex = 0
            tts.speakOutShort("${c.name}. Звонить?")
            handler.postDelayed({ startListening() }, 100L)
            tts.scheduleConfirmBeep()
            startChoosingTimeout()
            return
        }
        contactList = contacts
        contactIndex = 0
        choosingContact = true
        val first = contacts[0]
        val ordinal = ordinalWord(1)
        tts.speakOutShort("Нашёл ${contacts.size}. $ordinal ${first.name}. Звонить?")
        handler.postDelayed({ startListening() }, 100L)
        tts.scheduleConfirmBeep()
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

    // ==================== ЗАПИСЬ СЛОВА ====================
    private fun startWordRecording(fieldName: String) {
        recordingFieldName = fieldName
        recordingMode = true
        tts.speakOut("Говорите")
        handler.postDelayed({
            sendWordResult("")
        }, 5000L)
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

    // ==================== ВСПОМОГАТЕЛЬНОЕ ====================
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
}
