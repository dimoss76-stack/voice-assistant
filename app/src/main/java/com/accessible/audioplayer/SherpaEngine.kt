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

        // Проверяем наличие файлов
        val encoderFile = File(modelDir, "am-onnx/encoder.int8.onnx")
        val decoderFile = File(modelDir, "am-onnx/decoder.int8.onnx")
        val joinerFile = File(modelDir, "am-onnx/joiner.int8.onnx")
        val tokensFile = File(modelDir, "lang/tokens.txt")

        Log.e("MYVOSK", "encoder: ${encoderFile.exists()} (${encoderFile.absolutePath})")
        Log.e("MYVOSK", "decoder: ${decoderFile.exists()}")
        Log.e("MYVOSK", "joiner: ${joinerFile.exists()}")
        Log.e("MYVOSK", "tokens: ${tokensFile.exists()}")

        if (!encoderFile.exists() || !decoderFile.exists() || !joinerFile.exists() || !tokensFile.exists()) {
            onErrorCallback(Exception("Не все файлы модели на месте"))
            return
        }

        try {
            Log.e("MYVOSK", "Создаю конфигурацию...")
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoderFile.absolutePath,
                        decoder = decoderFile.absolutePath,
                        joiner = joinerFile.absolutePath,
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    debug = true,
                    // Убираем modelType, чтобы проверить, не в нём ли дело
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(false, 0.0f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 0.0f),
                ),
                decodingMethod = "greedy_search"
            )

            Log.e("MYVOSK", "Создаю OnlineRecognizer...")
            recognizer = OnlineRecognizer(null, config)
            Log.e("MYVOSK", "OnlineRecognizer создан")
            
            stream = recognizer?.createStream()
            Log.e("MYVOSK", "Stream создан")
            
            isInitialized = true
            Log.e("MYVOSK", "✅ Sherpa-ONNX успешно инициализирован")
            onModelReady?.invoke()
        } catch (e: Exception) {
            Log.e("MYVOSK", "❌ Ошибка инициализации Sherpa: ${e.message}", e)
            onErrorCallback(e)
        } catch (t: Throwable) {
            // Ловим даже ошибки нативного уровня (OutOfMemoryError и т.д.)
            Log.e("MYVOSK", "❌ КРИТИЧЕСКАЯ ошибка (Throwable): ${t.message}", t)
            onErrorCallback(Exception("Критическая ошибка: ${t.message}"))
        }
    }

    fun startListening(activation: String, confirm: String, shutdown: String) {
        if (!isInitialized) {
            Log.e("MYVOSK", "Sherpa не инициализирован, пропускаю startListening")
            return
        }
        Log.e("MYVOSK", "Sherpa: старт прослушивания")
        stream?.release()
        stream = recognizer?.createStream()
    }

    fun prepareListeningForNames(names: List<String>) {
        startListening("", "", "")
    }

    fun startListeningNow() {}

    fun acceptAudio(buffer: ShortArray, read: Int) {
        if (!isInitialized) return
        val currentStream = stream ?: return
        val currentRecognizer = recognizer ?: return

        val floatBuffer = FloatArray(read) { buffer[it] / 32768.0f }
        currentStream.acceptWaveform(floatBuffer, 16000)

        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
        }

        val text = currentRecognizer.getResult(currentStream)?.text?.trim()?.lowercase()
        if (!text.isNullOrEmpty()) {
            Log.e("MYVOSK", "Sherpa распознал: '$text'")
            onResultCallback(text)
        }

        if (currentRecognizer.isEndpoint(currentStream)) {
            Log.e("MYVOSK", "Sherpa: конец фразы")
            currentStream.release()
            stream = currentRecognizer.createStream()
        }
    }

    fun startListeningForHangup() { startListening("", "", "") }
    fun startListeningForStop() { startListening("", "", "") }

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

    fun recordSingleWord(durationMs: Long): String = ""
}
