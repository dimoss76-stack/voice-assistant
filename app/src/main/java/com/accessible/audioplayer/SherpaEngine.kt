package com.accessible.audioplayer

import android.content.Context
import android.os.Environment
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

class SherpaEngine(
    private val context: Context,
    private val onResultCallback: (String) -> Unit,
    private val onErrorCallback: (Exception) -> Unit
) {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var isInitialized = false

    // Пути к файлам модели (ваша большая модель)
    private fun getModelDir(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(publicDownloads, "VoiceAssistant"), "vosk-model-small-streaming-ru")
    }

    fun init() {
        Log.e("MYVOSK", "Инициализация Sherpa-ONNX...")
        val modelDir = getModelDir()
        if (!modelDir.exists()) {
            onErrorCallback(Exception("Папка модели не найдена: ${modelDir.absolutePath}"))
            return
        }

        try {
            // Конфигурация для потокового распознавания (Streaming)
            // Используем int8 версии файлов, они легче и быстрее
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, "am-onnx/encoder.int8.onnx").absolutePath,
                        decoder = File(modelDir, "am-onnx/decoder.int8.onnx").absolutePath,
                        joiner = File(modelDir, "am-onnx/joiner.int8.onnx").absolutePath,
                    ),
                    tokens = File(modelDir, "lang/tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu", // или "nnapi" для ускорения
                    debug = 1,
                ),
                // Включаем определение конца фразы, чтобы понимать, когда пользователь закончил
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4, 0.0f), // правило для тишины
                    rule2 = EndpointRule(true, 1.2, 0.0f),  // правило для речи
                    rule3 = EndpointRule(false, 0.0, 0.0f),
                ),
                decodingMethod = "greedy_search"
            )

            recognizer = OnlineRecognizer(null, config)
            stream = recognizer?.createStream()
            isInitialized = true
            Log.e("MYVOSK", "✅ Sherpa-ONNX успешно инициализирован")
            
            // Сообщаем VoiceService, что модель готова
            onModelReady?.invoke()
        } catch (e: Exception) {
            Log.e("MYVOSK", "❌ Ошибка инициализации Sherpa: ${e.message}", e)
            onErrorCallback(e)
        }
    }

    var onModelReady: (() -> Unit)? = null

    // Этот метод будет вызываться из VoiceService, когда нужно начать слушать
    fun startListening(activation: String, confirm: String, shutdown: String) {
        if (!isInitialized) return
        Log.e("MYVOSK", "Sherpa: старт прослушивания")
        // Сбрасываем стрим, чтобы начать новую сессию распознавания
        stream?.release()
        stream = recognizer?.createStream()
    }

    // Этот метод вызывается из VoiceService, когда мы ждём имя контакта
    fun prepareListeningForNames(names: List<String>) {
        // В sherpa-onnx можно использовать hotwords для повышения точности,
        // но пока просто перезапустим стрим для нового распознавания
        startListening("", "", "")
    }

    fun startListeningNow() {
        // Ничего не делаем, стрим уже создан
    }

    // Метод для получения аудио-данных. Вам нужно будет вызвать его из AudioRecord
    fun acceptAudio(buffer: ShortArray, read: Int) {
        if (!isInitialized || stream == null) return
        // Sherpa-onnx ожидает float массив в диапазоне [-1, 1]
        val floatBuffer = FloatArray(read) { buffer[it] / 32768.0f }
        stream?.acceptWaveform(floatBuffer, 16000)
        
        // Проверяем, не закончилась ли фраза
        if (recognizer?.isEndpoint(stream) == true) {
            val text = recognizer?.getResult(stream)?.text?.trim()?.lowercase()
            if (!text.isNullOrEmpty()) {
                Log.e("MYVOSK", "Sherpa распознал: '$text'")
                onResultCallback(text)
            }
            // Сбрасываем стрим для следующей фразы
            stream?.release()
            stream = recognizer?.createStream()
        }
    }

    fun startListeningForHangup() {
        // Логика для мониторинга звонка (можно оставить простой перезапуск)
        startListening("", "", "")
    }

    fun startListeningForStop() {
        startListening("", "", "")
    }

    fun stop() {
        stream?.release()
        stream = null
    }

    fun release() {
        stop()
        recognizer?.release()
        recognizer = null
        isInitialized = false
    }

    fun recordSingleWord(durationMs: Long): String {
        // Пока заглушка, для записи слов в настройках можно использовать Vosk
        // или реализовать отдельный поток с AudioRecord
        return ""
    }
}
