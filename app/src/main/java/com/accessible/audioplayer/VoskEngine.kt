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
            Log.e("MYVOSK", "Найдена скачанная модель")
            loadModelFromDir(modelDownloader.getModelDir())
        } else {
            Log.e("MYVOSK", "Модель не найдена, начинаю загрузку...")
            modelDownloader.downloadAndUnzip(
                onProgress = { msg -> Log.e("MYVOSK", msg) },
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
            // Логируем, что лежит в папке — для отладки
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

    // ... (Остальные методы: recordSingleWord, startListening, prepareListeningForNames, startListeningNow, startListeningForHangup, startListeningForStop, stop, release, onResult, onPartialResult, onFinalResult, onError, onTimeout) 
    // Оставьте их без изменений, как в вашей текущей версии.
    // Если нужно, я могу прислать их тоже, но они не менялись.
}
