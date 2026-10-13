package com.accessible.audioplayer

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: VoiceSettings
    private lateinit var statusText: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var btnDownloadLarge: Button
    private lateinit var btnToggleLarge: Button
    private lateinit var btnDeleteLarge: Button

    private var editActivation: EditText? = null
    private var editConfirm: EditText? = null
    private var editTime: EditText? = null
    private var editDate: EditText? = null
    private var editShutdown: EditText? = null

    private var currentFieldName: String = ""
    private var downloader: ModelDownloader? = null

    private val resultReceiver = object : ResultReceiver(Handler()) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val field = resultData?.getString("FIELD") ?: return
            val word = resultData.getString("WORD") ?: ""
            if (field != currentFieldName) return

            if (word.isNotEmpty()) {
                val target = when (field) {
                    "activation" -> editActivation
                    "confirm" -> editConfirm
                    "time" -> editTime
                    "date" -> editDate
                    "shutdown" -> editShutdown
                    else -> null
                }
                target?.setText(word)
                statusText.text = "Распознано: $word (можно исправить вручную)"
            } else {
                statusText.text = "Не расслышал, попробуйте ещё раз"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = VoiceSettings(this)

        editActivation = findViewById(R.id.editActivation)
        editConfirm = findViewById(R.id.editConfirm)
        editTime = findViewById(R.id.editTime)
        editDate = findViewById(R.id.editDate)
        editShutdown = findViewById(R.id.editShutdown)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnBack = findViewById<Button>(R.id.btnBack)
        statusText = findViewById(R.id.statusText)
        modelStatusText = findViewById(R.id.modelStatusText)
        btnDownloadLarge = findViewById(R.id.btnDownloadLarge)
        btnToggleLarge = findViewById(R.id.btnToggleLarge)
        btnDeleteLarge = findViewById(R.id.btnDeleteLarge)

        editActivation?.setText(settings.activationWord)
        editConfirm?.setText(settings.confirmWord)
        editTime?.setText(settings.timeWord)
        editDate?.setText(settings.dateWord)
        editShutdown?.setText(settings.shutdownWord)

        findViewById<Button>(R.id.btnRecordActivation).setOnClickListener { startRecordingFor("activation") }
        findViewById<Button>(R.id.btnRecordConfirm).setOnClickListener { startRecordingFor("confirm") }
        findViewById<Button>(R.id.btnRecordTime).setOnClickListener { startRecordingFor("time") }
        findViewById<Button>(R.id.btnRecordDate).setOnClickListener { startRecordingFor("date") }
        findViewById<Button>(R.id.btnRecordShutdown).setOnClickListener { startRecordingFor("shutdown") }

        btnSave.setOnClickListener {
            settings.activationWord = editActivation?.text.toString()
            settings.confirmWord = editConfirm?.text.toString()
            settings.timeWord = editTime?.text.toString()
            settings.dateWord = editDate?.text.toString()
            settings.shutdownWord = editShutdown?.text.toString()
            Toast.makeText(this, "Сохранено. Перезапустите помощника.", Toast.LENGTH_LONG).show()
            finish()
        }

        btnBack.setOnClickListener { finish() }

        downloader = ModelDownloader(this,
            onProgress = { msg -> runOnUiThread { statusText.text = msg } },
            onDone = { success -> runOnUiThread { updateModelUI(); if (success) statusText.text = "Готово!" } }
        )

        setupModelButtons()
        updateModelUI()
    }

    private fun setupModelButtons() {
        btnDownloadLarge.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Скачать улучшенную модель?")
                .setMessage("Размер: 500 МБ. Распакуется до 1.8 ГБ.\n\nМодель сохранится в папку «VoiceAssistant» на внутренней памяти. Её можно будет перенести на другой телефон через флешку.\n\nРазрешить скачивание через мобильный интернет, если Wi-Fi недоступен?")
                .setPositiveButton("Да, разрешить мобильный") { _, _ -> downloader?.startDownload(true) }
                .setNeutralButton("Только Wi-Fi") { _, _ -> downloader?.startDownload(false) }
                .setNegativeButton("Отмена", null)
                .show()
        }
        btnToggleLarge.setOnClickListener {
            settings.useLargeModel = !settings.useLargeModel
            updateModelUI()
            Toast.makeText(this, if (settings.useLargeModel) "Улучшенная модель включена. Перезапустите помощника." else "Вернулись к стандартной. Перезапустите помощника.", Toast.LENGTH_LONG).show()
        }
        btnDeleteLarge.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить улучшенную модель?")
                .setMessage("Будет освобождено ~1.8 ГБ.")
                .setPositiveButton("Удалить") { _, _ ->
                    settings.useLargeModel = false
                    downloader?.deleteLargeModel()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun updateModelUI() {
        val ready = downloader?.isLargeModelReady() == true
        val path = downloader?.getModelPath() ?: ""
        if (ready) {
            modelStatusText.text = (if (settings.useLargeModel) "Модель: улучшенная (используется)\n" else "Модель: улучшенная (отключена)\n") + "Папка: $path"
            btnDownloadLarge.visibility = android.view.View.GONE
            btnToggleLarge.visibility = android.view.View.VISIBLE
            btnDeleteLarge.visibility = android.view.View.VISIBLE
            btnToggleLarge.text = if (settings.useLargeModel) "ОТКЛЮЧИТЬ (вернуться к стандартной)" else "ИСПОЛЬЗОВАТЬ УЛУЧШЕННУЮ"
        } else {
            modelStatusText.text = "Модель: стандартная (45 МБ)"
            btnDownloadLarge.visibility = android.view.View.VISIBLE
            btnToggleLarge.visibility = android.view.View.GONE
            btnDeleteLarge.visibility = android.view.View.GONE
        }
    }

    private fun startRecordingFor(fieldName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
            return
        }

        currentFieldName = fieldName
        statusText.text = "Говорите…"

        val intent = Intent(this, VoiceService::class.java).apply {
            action = "RECORD_WORD"
            putExtra("FIELD", fieldName)
            putExtra("RECEIVER", resultReceiver)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
