package com.accessible.audioplayer

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException

class VoskEngine(
    private val context: Context,
    private val onResultCallback: (String) -> Unit,
    private val onErrorCallback: (Exception) -> Unit
) : RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private val modelDownloader = ModelDownloader(context)

    var onModelReady: (() -> Unit)? = null

    fun init() {
        Log.e("MYVOSK", "Инициализация Vosk...")

        if (modelDownloader.isModelReady()) {
            Log.e("MYVOSK", "Найдена модель в Download")
            loadModelFromDir(modelDownloader.getModelDir())
        } else {
            Log.e("MYVOSK", "Модель не найдена, начинаю загрузку...")
            modelDownloader.downloadAndUnzip(
                onProgress = { msg -> Log.e("MYVOSK", msg) },
                onProgressPercent = { percent -> Log.e("MYVOSK", "Загрузка: $percent%") },
                onComplete = { success ->
                    if (success) {
                        loadModelFromDir(modelDownloader.getModelDir())
                    } else {
                        onErrorCallback(Exception("Не удалось скачать модель"))
                    }
                }
            )
        }
    }

    private fun loadModelFromDir(modelDir: File) {
        try {
            Log.e("MYVOSK", "Содержимое папки модели:")
            modelDir.listFiles()?.forEach { Log.e("MYVOSK", " - ${it.name}") }

            model = Model(modelDir.absolutePath)
            Log.e("MYVOSK", "Модель успешно загружена из ${modelDir.absolutePath}")
            onModelReady?.invoke()
        } catch (e: IOException) {
            Log.e("MYVOSK", "Ошибка загрузки модели: ${e.message}", e)
            onErrorCallback(e)
        }
    }

    fun recordSingleWord(durationMs: Long = 5000L): String {
        val m = model ?: return ""
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
            return extractText(json)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка записи слова: ${e.message}", e)
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

    fun startListening(activation: String, confirm: String, shutdown: String) {
        try {
            model?.let {
                val grammar = """[
                    "$activation", "$confirm", "$shutdown",
                    "время", "часы", "час", "сколько",
                    "дата", "день", "число",
                    "да", "нет", "возьми", "сбрось", "отклони",
                    "взять", "поднять",
                    "позвони", "позвонить", "набери", "набрать",
                    "отмена", "отменить",
                    "контакты", "список", "покажи"
                ]"""
                val rec = Recognizer(it, 16000.0f, grammar)
                speechService?.stop()
                speechService?.shutdown()
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "Слушание с грамматикой запущено")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListening: ${e.message}", e)
        }
    }

    fun prepareListeningForNames(names: List<String>) {
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
