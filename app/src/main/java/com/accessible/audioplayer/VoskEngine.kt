package com.accessible.audioplayer

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class VoskEngine(
    private val context: Context,
    private val onResultCallback: (String) -> Unit,
    private val onErrorCallback: (Exception) -> Unit
) : RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    // Колбэк готовности модели
    var onModelReady: (() -> Unit)? = null

    // ========== ИНИЦИАЛИЗАЦИЯ ==========
    fun init() {
        Log.e("MYVOSK", "Загружаю модель")
        try {
            StorageService.unpack(context, "model-ru", "model",
                { m: Model ->
                    model = m
                    Log.e("MYVOSK", "Модель загружена")
                    onModelReady?.invoke()
                },
                { exception: IOException ->
                    Log.e("MYVOSK", "Ошибка модели: ${exception.message}", exception)
                    onErrorCallback(exception)
                }
            )
        } catch (e: Exception) {
            Log.e("MYVOSK", "Исключение: ${e.message}", e)
            onErrorCallback(e)
        }
    }

    // ========== ОСНОВНАЯ ГРАММАТИКА ==========
    fun startListening(activation: String, confirm: String, shutdown: String) {
        try {
            model?.let {
                val grammar = """[
                    "$activation",
                    "$confirm",
                    "$shutdown",
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

    // ========== ГРАММАТИКА ИМЁН ==========
    fun prepareListeningForNames(names: List<String>) {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null

            model?.let { m ->
                val namesJson = if (names.isEmpty()) {
                    ""
                } else {
                    names.joinToString(",") { "\"$it\"" }
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
                Log.e("MYVOSK", "Распознавание имён готово (${names.size} имён)")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка prepareListeningForNames: ${e.message}", e)
        }
    }

    // Запуск распознавания после паузы
    fun startListeningNow() {
        try {
            speechService?.startListening(this)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListeningNow: ${e.message}", e)
        }
    }

    // ========== ГРАММАТИКА МОНИТОРИНГА ==========
    fun startListeningForHangup() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null

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

    // ========== ГРАММАТИКА ЧТЕНИЯ КОНТАКТОВ ==========
    // Слушаем только "стоп", "отмена", "хватит" — чтобы прервать чтение
    fun startListeningForStop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null

            model?.let { m ->
                val grammar = """[
                    "стоп", "отмена", "отменить", "хватит",
                    "остановись", "прекрати"
                ]"""
                val rec = Recognizer(m, 16000.0f, grammar)
                speechService = SpeechService(rec, 16000.0f)
                speechService?.startListening(this)
                Log.e("MYVOSK", "✅ Грамматика 'стоп' активна")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListeningForStop: ${e.message}", e)
        }
    }

    // ========== ОСТАНОВКА ==========
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

    // ========== КОЛБЭКИ VOSK ==========
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
