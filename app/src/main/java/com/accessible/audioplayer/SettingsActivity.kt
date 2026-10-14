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
    private lateinit var btnToggleModel: Button

    private var editActivation: EditText? = null
    private var editConfirm: EditText? = null
    private var editTime: EditText? = null
    private var editDate: EditText? = null
    private var editShutdown: EditText? = null

    private var currentFieldName: String = ""

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
                statusText.text = "Распознано: $word"
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
        btnToggleModel = findViewById(R.id.btnDownloadLarge) // переиспользуем ID

        // Скрываем кнопки старой большой модели, если они есть
        findViewById<Button?>(R.id.btnToggleLarge)?.visibility = android.view.View.GONE
        findViewById<Button?>(R.id.btnDeleteLarge)?.visibility = android.view.View.GONE

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

        btnToggleModel.setOnClickListener {
            settings.modelMode = if (settings.modelMode == 0) 1 else 0
            updateModelUI()
            Toast.makeText(this, if (settings.modelMode == 1) "Включена streaming-модель. Перезапустите помощника." else "Вернулись к стандартной. Перезапустите помощника.", Toast.LENGTH_LONG).show()
        }

        updateModelUI()
    }

    private fun updateModelUI() {
        val streamingDir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "VoiceAssistant/vosk-model-small-streaming-ru"
        )
        val streamingReady = streamingDir.exists() && streamingDir.listFiles()?.isNotEmpty() == true

        if (!streamingReady) {
            modelStatusText.text = "Модель: стандартная (45 МБ)\nStreaming-модель не найдена.\nСкопируйте папку vosk-model-small-streaming-ru в Download/VoiceAssistant/"
            btnToggleModel.text = "STREAMING-МОДЕЛЬ НЕ НАЙДЕНА"
            btnToggleModel.isEnabled = false
            return
        }

        if (settings.modelMode == 1) {
            modelStatusText.text = "Модель: streaming (120 МБ, используется)"
            btnToggleModel.text = "ВЕРНУТЬСЯ К СТАНДАРТНОЙ"
        } else {
            modelStatusText.text = "Модель: стандартная (45 МБ)\nДоступна streaming-модель (120 МБ)"
            btnToggleModel.text = "ИСПОЛЬЗОВАТЬ STREAMING-МОДЕЛЬ"
        }
        btnToggleModel.isEnabled = true
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
