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
        // ВАЖНО: Ссылка должна быть прямой (заканчиваться на ?dl=1 для Dropbox)
        private const val MODEL_URL = "https://www.dropbox.com/scl/fi/y40r2n0pru4m8gxbcw9ya/model-ru.zip?rlkey=byri72u8rzypi3hw6i6q6vxsu&st=4l89uoje&dl=1"
        private const val ZIP_NAME = "model-ru.zip"
    }

    // Модель будет храниться во внутренней памяти приложения
    fun getModelDir(): File {
        return File(context.filesDir, "model-ru")
    }

    fun isModelReady(): Boolean {
        val dir = getModelDir()
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    fun downloadAndUnzip(onProgress: (String) -> Unit, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                onProgress("Начинаю загрузку модели...")
                Log.e("MYVOSK", "Начинаю загрузку модели...")

                val client = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .followRedirects(true) // Важно для Dropbox
                    .followSslRedirects(true)
                    .build()

                val request = Request.Builder().url(MODEL_URL).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e("MYVOSK", "Ошибка загрузки: ${response.code}")
                    onComplete(false)
                    return@Thread
                }

                val body = response.body
                if (body == null) {
                    onComplete(false)
                    return@Thread
                }

                // Сохраняем во временный файл
                val tempZip = File(context.cacheDir, ZIP_NAME)
                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        input.copyTo(output)
                    }
                }
                onProgress("Архив скачан, начинаю распаковку...")
                Log.e("MYVOSK", "Архив скачан, начинаю распаковку...")

                // Распаковка
                val targetDir = getModelDir()
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }
                targetDir.mkdirs()

                unzip(tempZip, targetDir)
                tempZip.delete()

                onProgress("Модель успешно установлена.")
                Log.e("MYVOSK", "Модель успешно установлена в ${targetDir.absolutePath}")
                onComplete(true)

            } catch (e: Exception) {
                Log.e("MYVOSK", "Ошибка при загрузке/распаковке: ${e.message}", e)
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
                val fileName = entry.name
                val outFile = File(targetDir, fileName)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Некорректный zip-архив")
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
