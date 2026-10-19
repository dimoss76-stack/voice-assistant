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
        private const val MODEL_URL = "https://www.dropbox.com/scl/fi/97tbjzsp8fh0djw4ufvhz/model-ru.zip?rlkey=x67yq9567mme348b2y63hd3qa&st=8c5hm8sa&dl=1"
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
}package com.accessible.audioplayer

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ModelDownloader(
    private val context: Context,
    private val onProgress: (String) -> Unit,
    private val onDone: (Boolean) -> Unit
) {

    private var cancelled = false

    companion object {
        
        private const val URL_COMPRESSED = "https://alphacephei.com/vosk/models/vosk-model-ru-0.22.zip"
        private const val ARCHIVE_NAME = "vosk-large.zip"
        private const val ROOT_FOLDER = "VoiceAssistant"
        private const val MODEL_SUBFOLDER = "model-large"
    }

    /**
     * Возвращает папку модели в публичном "Download":
     *   /sdcard/Download/VoiceAssistant/model-large/
     * Плюс: видно через USB-кабель и в любом файловом менеджере.
     */
    private fun getModelDir(): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val root = File(public, ROOT_FOLDER)
        if (!root.exists()) root.mkdirs()
        val model = File(root, MODEL_SUBFOLDER)
        if (!model.exists()) model.mkdirs()
        return model
    }

    fun getModelPath(): String = getModelDir().absolutePath

    fun isLargeModelReady(): Boolean {
        val dir = getModelDir()
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    fun deleteLargeModel() {
        val dir = getModelDir()
        if (dir.exists()) dir.deleteRecursively()
        // Удалим и родительскую папку VoiceAssistant, если пуста
        dir.parentFile?.let { if (it.listFiles()?.isEmpty() == true) it.delete() }
        onDone(true)
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun startDownload(allowMobile: Boolean) {
        cancelled = false
        Thread {
            try {
                val wifi = isWifiConnected()
                if (!wifi && !allowMobile) {
                    onProgress("Нет Wi-Fi. Отмените или разрешите мобильный интернет.")
                    onDone(false)
                    return@Thread
                }

                onProgress("Скачиваю модель (1.5 ГБ)…")
                val archive = File(context.cacheDir, ARCHIVE_NAME)
                val url = URL(URL_COMPRESSED)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 60000
                    readTimeout = 180000
                 }
                connection.connect()

                val totalSize = connection.contentLength.toLong()
                val input = BufferedInputStream(connection.inputStream)
                val output = FileOutputStream(archive)
                val buffer = ByteArray(8192)
                var totalRead: Long = 0
                var read: Int

                while (input.read(buffer).also { read = it } != -1 && !cancelled) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    if (totalSize > 0) {
                        val percent = (totalRead * 100 / totalSize).toInt()
                        onProgress("Скачано: $percent%")
                    }
                }
                output.close()
                input.close()

                if (cancelled) {
                    archive.delete()
                    onProgress("Отменено")
                    onDone(false)
                    return@Thread
                }

                onProgress("Распаковываю (это займёт 5–10 минут)…")
                val modelDir = getModelDir()
                // Очистим содержимое модели, если что-то есть
                modelDir.listFiles()?.forEach { it.deleteRecursively() }

                unzip(archive, modelDir)
                archive.delete()

                // Если внутри одна вложенная папка — поднимаем содержимое
                val children = modelDir.listFiles()
                if (children != null && children.size == 1 && children[0].isDirectory) {
                    val nested = children[0]
                    val files = nested.listFiles() ?: emptyArray()
                    for (f in files) {
                        f.renameTo(File(modelDir, f.name))
                    }
                    nested.delete()
                }

                onProgress("Готово! Модель: $modelDir")
                onDone(true)
            } catch (e: Exception) {
                Log.e("MYVOSK", "Ошибка скачивания: ${e.message}", e)
                onProgress("Ошибка: ${e.message}")
                onDone(false)
            }
        }.start()
    }

    fun cancel() {
        cancelled = true
    }

    private fun unzip(zipFile: File, targetDir: File) {
        val buffer = ByteArray(8192)
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                val outFile = File(targetDir, name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Некорректный zip")
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
