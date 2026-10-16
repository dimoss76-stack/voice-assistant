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

    // === ЧТЕНИЕ КОНТАКТОВ ===
    private var readingContacts = false
    private var contactsToRead: List<ContactCaller.Contact> = emptyList()
    private var contactsReadIndex = 0
    private var stopListeningActive = false

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
        readingContacts = false
        wakeLock?.let { if (it.isHeld) it.release() }
        vosk.release()
        tts.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== СИГНАЛЫ ОТ CallReceiver ====================
    fun onIncomingCall(callerName: String) {
        Log.e("MYVOSK", "onIncomingCall: $callerName")
        readingContacts = false
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
        readingContacts = false
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
        readingContacts = false
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
        stopListeningActive = false
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
        return choosingContact || awaitingName || awaitingAnswer ||
               inCallMonitoring || isActivated || readingContacts
    }

    // ==================== ЧТЕНИЕ КОНТАКТОВ ====================
    private fun startReadingContacts() {
        val contacts = contactCaller.getAllContactsSorted()
        if (contacts.isEmpty()) {
            tts.beepError()
            tts.speakOutShort("Контакты не найдены")
            return
        }

        readingContacts = true
        contactsToRead = contacts
        contactsReadIndex = 0

        val count = contacts.size
        val countWord = when {
            count % 10 == 1 && count % 100 != 11 -> "$count контакт"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "$count контакта"
            else -> "$count контактов"
        }

        tts.speakOut("Всего $countWord. Слушайте.")
        // После вступления — начинаем перечисление
        val delayAfterIntro = tts.ignoreUntil - System.currentTimeMillis() + 300L
        handler.postDelayed({
            vosk.startListeningForStop()
            stopListeningActive = true
            readNextContact()
        }, delayAfterIntro.coerceAtLeast(3000L))
    }

    private fun readNextContact() {
        if (!readingContacts) return

        if (contactsReadIndex >= contactsToRead.size) {
            // Конец списка
            readingContacts = false
            stopListeningActive = false
            tts.beepOk()
            tts.speakOutShort("Конец списка")
            handler.postDelayed({ startListening() }, 3000L)
            return
        }

        val contact = contactsToRead[contactsReadIndex]
        contactsReadIndex++

        Log.e("MYVOSK", "Читаю контакт ${contactsReadIndex}/${contactsToRead.size}: '${contact.name}'")

        // Говорим имя
        tts.speakOutShort(contact.name)

        // После TTS — пауза + бип + пауза для "стоп"
        val delayAfterTts = (tts.ignoreUntil - System.currentTimeMillis()) + 500L
        handler.postDelayed({
            if (!readingContacts) return@postDelayed
            // Бип — можно сказать "стоп"
            tts.beepListen()
            // Пауза после бипа — слушаем "стоп"
            handler.postDelayed({
                if (!readingContacts) return@postDelayed
                readNextContact()
            }, 2000L)
        }, delayAfterTts.coerceAtLeast(2000L))
    }

    private fun stopReadingContacts() {
        Log.e("MYVOSK", "Остановка чтения контактов")
        readingContacts = false
        stopListeningActive = false
        tts.beepOk()
        tts.speakOutShort("Остановлено")
        handler.postDelayed({ startListening() }, 3000L)
    }

    // ==================== ОБРАБОТКА РАСПОЗНАННОГО ТЕКСТА ====================
    private fun handleRecognizedText(text: String) {
        Log.e("MYVOSK", "Распознано: '$text' (awaitingAnswer=$awaitingAnswer, choosingContact=$choosingContact, awaitingName=$awaitingName, inCall=$inCallMonitoring, reading=$readingContacts, ignore=${System.currentTimeMillis() < tts.ignoreUntil})")

        if (recordingMode) return

        if (System.currentTimeMillis() < tts.ignoreUntil) {
            Log.e("MYVOSK", "Игнорирую (эхо TTS): '$text'")
            return
        }

        // === ЧТЕНИЕ КОНТАКТОВ — слушаем только "стоп" ===
        if (readingContacts || stopListeningActive) {
            val words = text.split(" ").toSet()
            val isStop = words.contains("стоп") || words.contains("отмена") ||
                         words.contains("отменить") || words.contains("хватит") ||
                         words.contains("остановись") || words.contains("прекрати")
            if (isStop) {
                Log.e("MYVOSK", "✅ Команда 'стоп' во время чтения")
                stopReadingContacts()
            } else {
                Log.e("MYVOSK", "Во время чтения контактов игнорирую: '$text'")
            }
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
                readingContacts = false
                contactList = emptyList()
                contactIndex = 0
                cancelSilenceTimeout()
                cancelChoosingTimeout()
                tts.beepOk()
                tts.speakOutShort("Отменено")
                return
            }
        }

        // ОЖИДАНИЕ ИМЕНИ
        if (awaitingName) {
            cancelSilenceTimeout()
            Log.e("MYVOSK", "Распознавание имени: '$text'")

            val nameOnly = text.split(" ").firstOrNull()?.trim() ?: text
            val commandWords = listOf(
                "нет", "да", "отмена", "отменить", "позвони", "позвонить",
                "звонок", "трубку", "дата", "время", "день", "число",
                "завершить", "положить", "набери", "набрать",
                "контакты", "список", "покажи"
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
                tts.speakOutShort("Отве
