package com.accessible.audioplayer

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
    private var packetCounter = 0

    private var lastText = ""
    private val handler = Handler(Looper.getMainLooper())
    private val resetStreamRunnable = Runnable { resetStream() }
    private val checkResultRunnable = Runnable { checkResult() }

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

        val encoderFile = File(modelDir, "am-onnx/encoder.int8.onnx")
        val decoderFile = File(modelDir, "am-onnx/decoder.int8.onnx")
        val joinerFile = File(modelDir, "am-onnx/joiner.int8.onnx")
        val tokensFile = File(modelDir, "lang/tokens.txt")

        if (!encoderFile.exists() || !decoderFile.exists() || !joinerFile.exists() || !tokensFile.exists()) {
            onErrorCallback(Exception("Не все файлы модели на месте"))
            return
        }

        try {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoderFile.absolutePath,
                        decoder = decoderFile.absolutePath,
                        joiner = joinerFile.absolutePath,
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 1, // УМЕНЬШЕНО до 1, чтобы снизить нагрузку
                    provider = "cpu",
                    debug = true,
                ),
                // Отключаем endpoint, чтобы он не спамил
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 0.0f, 0.0f),
                    rule2 = EndpointRule(false, 0.0f, 0.0f),
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
        lastText = ""
        packetCounter = 0
        
        handler.removeCallbacks(resetStreamRunnable)
        handler.postDelayed(resetStreamRunnable, 3000L) // СБРОС КАЖДЫЕ 3 СЕКУНДЫ
        handler.removeCallbacks(checkResultRunnable)
        handler.postDelayed(checkResultRunnable, 1000L)
    }

    private fun resetStream() {
        if (!isInitialized) return
        Log.e("MYVOSK", "Sherpa: принудительный сброс стрима (пакетов обработано: $packetCounter)")
        stream?.release()
        stream = recognizer?.createStream()
        lastText = ""
        packetCounter = 0
        handler.postDelayed(resetStreamRunnable, 3000L)
    }

    private fun checkResult() {
        if (!isInitialized) return
        val currentStream = stream ?: return
        val currentRecognizer = recognizer ?: return

        val text = currentRecognizer.getResult(currentStream)?.text?.trim()?.lowercase() ?: ""
        if (text.isNotEmpty() && text != lastText) {
            lastText = text
            Log.e("MYVOSK", "Sherpa распознал: '$text'")
            onResultCallback(text)
        }
        handler.postDelayed(checkResultRunnable, 1000L)
    }

    fun prepareListeningForNames(names: List<String>) {
        startListening("", "", "")
    }

    fun startListeningNow() {}

    fun acceptAudio(buffer: ShortArray, read: Int) {
        if (!isInitialized) return
        val currentStream = stream ?: return
        val currentRecognizer = recognizer ?: return

        packetCounter++
        if (packetCounter % 50 == 0) {
            Log.e("MYVOSK", "Sherpa: обработано пакетов: $packetCounter")
        }

        val floatBuffer = FloatArray(read) { buffer[it] / 32768.0f }
        currentStream.acceptWaveform(floatBuffer, 16000)

        while (currentRecognizer.isReady(currentStream)) {
            currentRecognizer.decode(currentStream)
        }
    }

    fun startListeningForHangup() { startListening("", "", "") }
    fun startListeningForStop() { startListening("", "", "") }

    fun stop() {
        handler.removeCallbacks(resetStreamRunnable)
        handler.removeCallbacks(checkResultRunnable)
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
