package com.accessible.audioplayer

import android.content.Context
import android.os.Environment
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException

class VoskEngine(
    private val context: Context,
    private val onResultCallback: (String) -> Unit,
    private val onErrorCallback: (Exception) -> Unit
) : RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isUsingBigModel = false  // Флаг: реально ли загружена большая модель

    var onModelReady: (() -> Unit)? = null

    fun init() {
        Log.e("MYVOSK", "=== ИНИЦИАЛИЗАЦИЯ VOSK ===")
        val settings = VoiceSettings(context)
        val bigModelDir = getBigModelDir()
        val useBigModel = settings.modelMode == 1 && bigModelDir.exists()

        Log.e("MYVOSK", "Режим в настройках: modelMode=${settings.modelMode}")
        Log.e("MYVOSK", "Путь к большой модели: ${bigModelDir.absolutePath}")
        Log.e("MYVOSK", "Большая модель существует: ${bigModelDir.exists()}")
        Log.e("MYVOSK", "Использовать большую модель: $useBigModel")

        if (useBigModel) {
            Log.e("MYVOSK", "Попытка загрузить БОЛЬШУЮ модель...")
            try {
                // ПРОВЕРКА: что лежит в папке?
                if (bigModelDir.exists()) {
                    Log.e("MYVOSK", "Содержимое папки большой модели:")
                    bigModelDir.listFiles()?.forEach { Log.e("MYVOSK", " - ${it.name}") }
                }

                model = Model(bigModelDir.absolutePath)
                isUsingBigModel = true
                Log.e("MYVOSK", "✅ БОЛЬШАЯ модель успешно загружена")
                onModelReady?.invoke()
            } catch (e: IOException) {
                Log.e("MYVOSK", "❌ Ошибка загрузки большой модели: ${e.message}. Откат на маленькую.")
                loadSmallModel()
            }
        } else {
            Log.e("MYVOSK", "Загрузка МАЛЕНЬКОЙ модели из assets...")
            loadSmallModel()
        }
    }

    private fun loadSmallModel() {
        try {
            Log.e("MYVOSK", "Вызов StorageService.unpack для маленькой модели...")
            StorageService.unpack(context, "model-ru", "model",
                { m: Model ->
                    model = m
                    isUsingBigModel = false
                    Log.e("MYVOSK", "✅ МАЛЕНЬКАЯ модель успешно загружена из assets")
                    onModelReady?.invoke()
                },
                { exception: IOException ->
                    Log.e("MYVOSK", "❌ Ошибка загрузки маленькой модели: ${exception.message}", exception)
                    onErrorCallback(exception)
                }
            )
        } catch (e: Exception) {
            Log.e("MYVOSK", "❌ Исключение при загрузке маленькой модели: ${e.message}", e)
            onErrorCallback(e)
        }
    }

    fun getBigModelDir(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(publicDownloads, "VoiceAssistant"), "vosk-model-small-streaming-ru")
    }

    // ========== ЗАПИСЬ СЛОВА (для настроек) ==========
    fun recordSingleWord(durationMs: Long = 5000L): String {
        val m = model ?: run {
            Log.e("MYVOSK", "recordSingleWord: модель не загружена")
            return ""
        }
        Log.e("MYVOSK", "recordSingleWord: начало записи (${durationMs}мс)")
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
                Log.e("MYVOSK", "recordSingleWord: AudioRecord не инициализирован")
                return ""
            }
            recognizer = Recognizer(m, 16000.0f)
            audioRecord.startRecording()
            val buffer = ShortArray(bufferSize)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < durationMs) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) recognizer.acceptWaveForm(buffer, read)
            }
            val json = recognizer.finalResult
            val text = extractText(json)
            Log.e("MYVOSK", "recordSingleWord: распознано '$text'")
            return text
        } catch (e: Exception) {
            Log.e("MYVOSK", "recordSingleWord: ошибка ${e.message}", e)
            return ""
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            try { recognizer?.close() } catch (_: Exception) {}
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

    // ========== ЗАПУСК РАСПОЗНАВАНИЯ ==========
    fun startListening(activation: String, confirm: String, shutdown: String) {
        Log.e("MYVOSK", "=== startListening ===")
        Log.e("MYVOSK", "isUsingBigModel=$isUsingBigModel")
        try {
            model?.let { m ->
                val recognizer = if (isUsingBigModel) {
                    Log.e("MYVOSK", "Создаю Recognizer БЕЗ грамматики (большая модель)")
                    Recognizer(m, 16000.0f)
                } else {
                    val grammar = """[
                        "$activation",
                        "$confirm",
                        "$shutdown",
                        "время", "часы", "час", "сколько",
                        "дата", "день", "число",
                        "да", "нет", "ответь", "сбрось", "отклони",
                        "[unk]"
                    ]"""
                    Log.e("MYVOSK", "Создаю Recognizer С грамматикой (маленькая модель)")
                    Log.e("MYVOSK", "Грамматика: $grammar")
                    Recognizer(m, 16000.0f, grammar)
                }

                speechService?.stop()
                speechService?.shutdown()
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "Слушание запущено")
            } ?: Log.e("MYVOSK", "ОШИБКА: model == null в startListening")
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListening: ${e.message}", e)
        }
    }

    fun prepareListeningForNames(names: List<String>) {
        Log.e("MYVOSK", "prepareListeningForNames: ${names.size} имён")
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            model?.let { m ->
                val namesJson = if (names.isEmpty()) "" else names.joinToString(",") { "\"$it\"" }
                val grammar = if (namesJson.isEmpty()) null else """[
                    $namesJson,
                    "отмена", "отменить", "стоп", "хватит", "назад"
                ]"""
                val rec = if (grammar != null) Recognizer(m, 16000.0f, grammar) else Recognizer(m, 16000.0f)
                speechService = SpeechService(rec, 16000.0f)
                Log.e("MYVOSK", "Распознавание имён готово (${names.size} имён)")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка prepareListeningForNames: ${e.message}", e)
        }
    }

    fun startListeningNow() {
        try {
            speechService?.startListening(this)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListeningNow: ${e.message}", e)
        }
    }

    fun startListeningForHangup() {
        Log.e("MYVOSK", "startListeningForHangup")
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            model?.let { m ->
                val grammar = """[
                    "завершить этот звонок", "завершить звонок", "положить трубку"
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

    fun startListeningForStop() {
        Log.e("MYVOSK", "startListeningForStop")
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            model?.let { m ->
                val grammar = """["стоп", "отмена", "отменить", "хватит", "остановись", "прекрати"]"""
                val rec = Recognizer(m, 16000.0f, grammar)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "✅ Грамматика 'стоп' активна")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListeningForStop: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
        } catch (_: Exception) {}
    }

    fun release() {
        stop()
        model = null
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis == null) return
        val text = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"").trim().lowercase()
        if (text.isEmpty()) return
        onResultCallback(text)
    }

    override fun onPartialResult(hypothesis: String?) {}
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        Log.e("MYVOSK", "Vosk onError: ${exception?.message}", exception)
        exception?.let { onErrorCallback(it) }
    }
    override fun onTimeout() {}
}
