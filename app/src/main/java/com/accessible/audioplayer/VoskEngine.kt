package com.accessible.audioplayer

import android.content.Context
import android.os.Environment
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

    var onModelReady: (() -> Unit)? = null

    fun init() {
        Log.e("MYVOSK", "Инициализация Vosk...")
        val settings = VoiceSettings(context)
        val bigModelDir = getBigModelDir()
        val useBigModel = settings.modelMode == 1 && bigModelDir.exists()

        if (useBigModel) {
            Log.e("MYVOSK", "Загружаю БОЛЬШУЮ (streaming) модель из ${bigModelDir.absolutePath}")
            try {
                model = Model(bigModelDir.absolutePath)
                Log.e("MYVOSK", "Большая модель успешно загружена")
                onModelReady?.invoke()
            } catch (e: IOException) {
                Log.e("MYVOSK", "Ошибка загрузки большой модели: ${e.message}. Откат на маленькую.")
                loadSmallModel()
            }
        } else {
            Log.e("MYVOSK", "Загружаю МАЛЕНЬКУЮ модель из assets")
            loadSmallModel()
        }
    }

    private fun loadSmallModel() {
        // Используем StorageService для маленькой модели из assets
        try {
            org.vosk.android.StorageService.unpack(context, "model-ru", "model",
                { m: Model ->
                    model = m
                    Log.e("MYVOSK", "Маленькая модель успешно загружена")
                    onModelReady?.invoke()
                },
                { exception: IOException ->
                    Log.e("MYVOSK", "Ошибка загрузки маленькой модели: ${exception.message}", exception)
                    onErrorCallback(exception)
                }
            )
        } catch (e: Exception) {
            Log.e("MYVOSK", "Исключение при загрузке маленькой модели: ${e.message}", e)
            onErrorCallback(e)
        }
    }

    fun getBigModelDir(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(publicDownloads, "VoiceAssistant"), "vosk-model-small-streaming-ru")
    }

    // ========== ЗАПУСК РАСПОЗНАВАНИЯ ==========
    fun startListening(activation: String, confirm: String, shutdown: String) {
        try {
            model?.let { m ->
                val settings = VoiceSettings(context)
                val isStreaming = settings.modelMode == 1 && getBigModelDir().exists()

                val recognizer = if (isStreaming) {
                    // ВАЖНО: Streaming-модель НЕ ПОДДЕРЖИВАЕТ грамматику. Vosk падает в Model::Ref().
                    Log.e("MYVOSK", "Streaming-модель: распознаватель БЕЗ грамматики")
                    Recognizer(m, 16000.0f)
                } else {
                    // Для маленькой модели используем грамматику для точности
                    val grammar = """[
                        "$activation",
                        "$confirm",
                        "$shutdown",
                        "время", "часы", "час", "сколько",
                        "дата", "день", "число",
                        "да", "нет", "ответь", "сбрось", "отклони",
                        "[unk]"
                    ]"""
                    Log.e("MYVOSK", "Маленькая модель: грамматика $grammar")
                    Recognizer(m, 16000.0f, grammar)
                }

                speechService?.stop()
                speechService?.shutdown()
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "Слушание запущено")
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
