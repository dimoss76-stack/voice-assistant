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
    private var decodeCount = 0

    var onModelReady: (() -> Unit)? = null

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
                    provider = "cpu",
                    debug = true,
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.2f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 0.0f),
                ),
                decodingMethod = "greedy_search"
            )

            recognizer = OnlineRecognizer(null, config)
            stream = recognizer?.createStream()
            isInitialized = true
            Log.e("MYVOSK", "✅ Sherpa-ONNX успешно инициализирован")
            onModelReady?.invoke()
        } catch (e: Exception) {
            Log.e("MYVOSK", "❌ Ошибка инициализации Sherpa: ${e.message}", e)
            onErrorCallback(e)
        }
    }

    fun startListening(activation: String, confirm: String, shutdown: String) {
        if (!isInitialized) return
        Log.e("MYVOSK", "Sherpa: старт прослушивания")
        stream?.release()
        stream = recognizer?.createStream()
    }

    fun prepareListeningForNames(names: List<String>) {
        startListening("", "", "")
    }

    fun startListeningNow() {
        // Ничего не делаем
    }

    fun acceptAudio(buffer: ShortArray, read: Int) {
        if (!isInitialized) return
        val currentStream = stream ?: return
        val currentRecognizer = recognizer ?: return

        val floatBuffer = FloatArray(read) { buffer[it] / 32768.0f }
        currentStream.acceptWaveform(floatBuffer, 16000)

        // Декодируем, пока это возможно
        var readyCount = 0
        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
            readyCount++
        }
        
        if (readyCount > 0) {
            decodeCount++
            if (decodeCount % 50 == 0) {
                Log.e("MYVOSK", "Sherpa: декодировано $decodeCount раз (последний readyCount=$readyCount)")
            }
        }

        val text = currentRecognizer.getResult(currentStream)?.text?.trim()?.lowercase()
        if (!text.isNullOrEmpty()) {
            Log.e("MYVOSK", "Sherpa распознал: '$text'")
            onResultCallback(text)
        }

        if (currentRecognizer.isEndpoint(currentStream)) {
            Log.e("MYVOSK", "Sherpa: достигнут конец фразы (endpoint)")
            currentStream.release()
            stream = currentRecognizer.createStream()
        }
    }

    fun startListeningForHangup() {
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
        return ""
    }
}
