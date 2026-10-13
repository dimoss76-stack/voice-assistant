package com.accessible.audioplayer

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VoiceRecorder(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {

    private var recordThread: Thread? = null
    private var isRecording = false

    fun startRecording(durationMs: Long = 5000L) {
        isRecording = true

        recordThread = Thread {
            var audioRecord: AudioRecord? = null
            var recognizer: Recognizer? = null
            var model: Model? = null

            try {
                // 1. Проверяем/распаковываем модель
                val modelDir = File(context.filesDir, "model")
                if (!modelDir.exists() || modelDir.list() == null || modelDir.list()!!.isEmpty()) {
                    onStatus("Готовлю модель (один раз)…")
                    val latch = CountDownLatch(1)
                    var unpacked = false

                    StorageService.unpack(
                        context,
                        "model-ru",
                        "model",
                        { m ->
                            Log.e("MYVOSK", "Модель распакована для записи")
                            unpacked = true
                            latch.countDown()
                        },
                        { exception: IOException ->
                            Log.e("MYVOSK", "Ошибка распаковки модели: ${exception.message}", exception)
                            latch.countDown()
                        }
                    )

                    // Ждём распаковку до 30 секунд
                    latch.await(30, TimeUnit.SECONDS)

                    if (!unpacked || !modelDir.exists()) {
                        Log.e("MYVOSK", "Не удалось распаковать модель")
                        onStatus("Модель недоступна")
                        isRecording = false
                        onFinished()
                        return@Thread
                    }
                }

                onStatus("Говорите…")
                Log.e("MYVOSK", "Загружаю модель из ${modelDir.absolutePath}")

                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model, 16000.0f)

                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 4
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("MYVOSK", "AudioRecord не инициализирован")
                    onStatus("Ошибка микрофона")
                    isRecording = false
                    onFinished()
                    return@Thread
                }

                audioRecord.startRecording()
                Log.e("MYVOSK", "Запись началась, длительность $durationMs мс")

                val buffer = ShortArray(bufferSize)
                val startTime = System.currentTimeMillis()

                while (isRecording && System.currentTimeMillis() - startTime < durationMs) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        recognizer.acceptWaveForm(buffer, read)
                    }
                }

                val resultJson = recognizer.finalResult
                Log.e("MYVOSK", "Vosk finalResult: $resultJson")

                val text = extractText(resultJson)
                if (text.isNotEmpty()) {
                    Log.e("MYVOSK", "Vosk распознал: '$text'")
                    onResult(text)
                } else {
                    onStatus("Не расслышал, попробуйте ещё")
                }
            } catch (e: Exception) {
                Log.e("MYVOSK", "Ошибка записи: ${e.message}", e)
                onStatus("Ошибка: ${e.message}")
            } finally {
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                try { recognizer?.close() } catch (_: Exception) {}
                try { model?.close() } catch (_: Exception) {}
                isRecording = false
                onFinished()
            }
        }
        recordThread?.start()
    }

    fun stopRecording() {
        isRecording = false
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
        } catch (e: Exception) {
            ""
        }
    }
}
