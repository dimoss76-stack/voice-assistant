package com.accessible.audioplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
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
        private const val URL_COMPRESSED = "https://alphacephei.com/vosk/models/vosk-model-ru-0.42-compress.zip"
        private const val ARCHIVE_NAME = "vosk-large.zip"
        private const val FOLDER_NAME = "model-large"
    }

    fun isLargeModelReady(): Boolean {
        val dir = File(context.filesDir, FOLDER_NAME)
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    fun deleteLargeModel() {
        val dir = File(context.filesDir, FOLDER_NAME)
        if (dir.exists()) dir.deleteRecursively()
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

                onProgress("Скачиваю модель (500 МБ)…")
                val archive = File(context.cacheDir, ARCHIVE_NAME)
                val url = URL(URL_COMPRESSED)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 30000
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

                // Распаковка
                onProgress("Распаковываю…")
                val modelDir = File(context.filesDir, FOLDER_NAME)
                if (modelDir.exists()) modelDir.deleteRecursively()
                modelDir.mkdirs()

                unzip(archive, modelDir)
                archive.delete()

                // Проверка: внутри может быть вложенная папка
                val children = modelDir.listFiles()
                if (children != null && children.size == 1 && children[0].isDirectory) {
                    // Перемещаем содержимое вложенной папки на уровень выше
                    val nested = children[0]
                    val files = nested.listFiles() ?: emptyArray()
                    for (f in files) {
                        f.renameTo(File(modelDir, f.name))
                    }
                    nested.delete()
                }

                onProgress("Готово! Модель установлена.")
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
                // Защита от zip slip
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
