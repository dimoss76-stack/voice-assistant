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
        // ВАЖНО: Убедитесь, что ссылка заканчивается на ?dl=1 (для Dropbox)
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
                Log.e("MYVOSK", "1. Создаю HTTP-клиент...")

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true) // Важно для Dropbox
                    .followSslRedirects(true)
                    .build()

                val request = Request.Builder().url(MODEL_URL).build()
                Log.e("MYVOSK", "2. Выполняю запрос к $MODEL_URL")
                
                val response = client.newCall(request).execute()
                Log.e("MYVOSK", "3. Получен ответ. Код: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e("MYVOSK", "ОШИБКА: Сервер вернул код ${response.code}")
                    onComplete(false)
                    return@Thread
                }

                val body = response.body
                if (body == null) {
                    Log.e("MYVOSK", "ОШИБКА: Тело ответа пустое")
                    onComplete(false)
                    return@Thread
                }

                val contentLength = body.contentLength()
                Log.e("MYVOSK", "4. Размер файла на сервере: $contentLength байт")

                val tempZip = File(context.cacheDir, ZIP_NAME)
                Log.e("MYVOSK", "5. Начинаю скачивание в ${tempZip.absolutePath}...")
                
                body.byteStream().use { input ->
                    FileOutputStream(tempZip).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.e("MYVOSK", "6. Файл скачан. Реальный размер: ${tempZip.length()} байт")

                // ПРОВЕРКА: Если файл слишком маленький, значит скачалась HTML-страница, а не ZIP
                if (tempZip.length() < 1024 * 1024) { // Меньше 1 МБ — это точно не наша модель
                    Log.e("MYVOSK", "ОШИБКА: Скачанный файл слишком мал (${tempZip.length()} байт). Вероятно, это HTML-страница Dropbox.")
                    onProgress("Ошибка: скачался не архив. Проверьте прямую ссылку.")
                    onComplete(false)
                    tempZip.delete()
                    return@Thread
                }

                onProgress("Архив скачан, начинаю распаковку...")
                
                val targetDir = getModelDir()
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }
                targetDir.mkdirs()

                Log.e("MYVOSK", "7. Начинаю распаковку архива...")
                unzip(tempZip, targetDir)
                tempZip.delete()
                Log.e("MYVOSK", "8. Распаковка завершена успешно!")

                onProgress("Модель успешно установлена.")
                onComplete(true)

            } catch (e: Exception) {
                Log.e("MYVOSK", "КРИТИЧЕСКАЯ ОШИБКА при загрузке/распаковке: ${e.message}", e)
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
                    throw SecurityException("Некорректный zip-архив (Zip Slip)")
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
