package com.accessible.audioplayer

import android.content.Context
import android.os.Environment
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class ModelDownloader(private val context: Context) {

    companion object {
        private const val MODEL_URL = "https://www.dropbox.com/scl/fi/6iif93kkyr5q17uwi4vjv/model-ru.zip?rlkey=in96kpf4hyylzfbfnjedt9v7o&st=jjoou9vo&dl=1"
        private const val ZIP_NAME = "model-ru.zip"
        private const val FOLDER_NAME = "vosk-model-small-streaming-ru"
    }

    // ПУТЬ: Download/VoiceAssistant/vosk-model-small-streaming-ru
    fun getModelDir(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val voiceAssistantDir = File(publicDownloads, "VoiceAssistant")
        return File(voiceAssistantDir, FOLDER_NAME)
    }

    fun isModelReady(): Boolean {
        val dir = getModelDir()
        if (!dir.exists() || dir.listFiles()?.isEmpty() != false) return false
        return File(dir, "am").exists()
    }

    fun downloadAndUnzip(
        onProgress: (String) -> Unit,
        onProgressPercent: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        Thread {
            try {
                onProgress("Начинаю загрузку модели...")
                Log.e("MYVOSK", "1. Создаю HTTP-клиент...")

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()

                val request = Request.Builder().url(MODEL_URL).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    onComplete(false)
                    return@Thread
                }

                val body = response.body ?: run {
                    onComplete(false)
                    return@Thread
                }

                val totalBytes = body.contentLength()
                val tempZip = File(context.cacheDir, ZIP_NAME)
                
                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            totalRead += bytes
                            if (totalBytes > 0) {
                                val percent = ((totalRead * 100) / totalBytes).toInt()
                                onProgressPercent(percent)
                            }
                        }
                    }
                }

                if (tempZip.length() < 10 * 1024 * 1024) {
                    onComplete(false)
                    tempZip.delete()
                    return@Thread
                }

                // Распаковка в Download
                val targetDir = getModelDir()
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()

                onProgress("Распаковка архива...")
                unzip(tempZip, targetDir)
                tempZip.delete()
                
                // Проверка, что папка am на месте
                if (!File(targetDir, "am").exists()) {
                    onComplete(false)
                    return@Thread
                }

                onProgress("Модель успешно установлена.")
                onComplete(true)

            } catch (e: Exception) {
                Log.e("MYVOSK", "КРИТИЧЕСКАЯ ОШИБКА: ${e.message}", e)
                onProgress("Ошибка загрузки модели.")
                onComplete(false)
            }
        }.start()
    }

    private fun unzip(zipFile: File, targetDir: File) {
        val buffer = ByteArray(8192)
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip Slip")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
