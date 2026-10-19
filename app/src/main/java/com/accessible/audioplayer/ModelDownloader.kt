package com.accessible.audioplayer

import android.content.Context
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
        // УБЕДИТЕСЬ, ЧТО ССЫЛКА ЗАКАНЧИВАЕТСЯ НА &dl=1
        private const val MODEL_URL = "https://www.dropbox.com/scl/fi/6iif93kkyr5q17uwi4vjv/model-ru.zip?rlkey=in96kpf4hyylzfbfnjedt9v7o&st=jjoou9vo&dl=1"
        private const val ZIP_NAME = "model-ru.zip"
    }

    fun getModelDir(): File {
        return File(context.filesDir, "model-ru")
    }

    fun isModelReady(): Boolean {
        val dir = getModelDir()
        if (!dir.exists() || dir.listFiles()?.isEmpty() != false) return false
        return File(dir, "am").exists()
    }

    fun downloadAndUnzip(onProgress: (String) -> Unit, onComplete: (Boolean) -> Unit) {
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
                Log.e("MYVOSK", "2. Запрос к $MODEL_URL")
                
                val response = client.newCall(request).execute()
                Log.e("MYVOSK", "3. Ответ: ${response.code}")

                if (!response.isSuccessful) {
                    onComplete(false)
                    return@Thread
                }

                val body = response.body ?: run {
                    onComplete(false)
                    return@Thread
                }

                val tempZip = File(context.cacheDir, ZIP_NAME)
                Log.e("MYVOSK", "4. Скачиваю...")
                
                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.e("MYVOSK", "5. Скачано: ${tempZip.length()} байт")

                if (tempZip.length() < 10 * 1024 * 1024) {
                    Log.e("MYVOSK", "ОШИБКА: Файл слишком мал. Это не ZIP!")
                    onComplete(false)
                    tempZip.delete()
                    return@Thread
                }

                val targetDir = getModelDir()
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()

                Log.e("MYVOSK", "6. Распаковка...")
                unzip(tempZip, targetDir)
                tempZip.delete()
                
                // ИСПРАВЛЕНИЕ: Если есть лишняя вложенная папка, поднимаем файлы наверх
                val files = targetDir.listFiles()
                if (files != null && files.size == 1 && files[0].isDirectory) {
                    Log.e("MYVOSK", "Найдена вложенная папка: ${files[0].name}. Поднимаю файлы...")
                    val nestedDir = files[0]
                    val innerFiles = nestedDir.listFiles()
                    if (innerFiles != null) {
                        for (file in innerFiles) {
                            file.renameTo(File(targetDir, file.name))
                        }
                        nestedDir.delete()
                    }
                }

                // Проверка, что папка 'am' на месте
                if (!File(targetDir, "am").exists()) {
                    Log.e("MYVOSK", "ОШИБКА: После распаковки нет папки 'am'! Архив битый.")
                    onComplete(false)
                    return@Thread
                }

                Log.e("MYVOSK", "7. Распаковка успешна, модель в ${targetDir.absolutePath}")
                onComplete(true)

            } catch (e: Exception) {
                Log.e("MYVOSK", "КРИТИЧЕСКАЯ ОШИБКА: ${e.message}", e)
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
