package com.accessible.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var checkShowCallButtons: CheckBox
    private lateinit var checkShutdownEnabled: CheckBox
    private lateinit var radioLayout: RadioGroup
    private lateinit var radioVertical: RadioButton
    private lateinit var radioHorizontal: RadioButton
    private lateinit var modelDownloader: ModelDownloader

    private var editActivation: EditText? = null
    private var editConfirm: EditText? = null
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
        modelDownloader = ModelDownloader(this)

        editActivation = findViewById(R.id.editActivation)
        editConfirm = findViewById(R.id.editConfirm)
        editShutdown = findViewById(R.id.editShutdown)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnBack = findViewById<Button>(R.id.btnBack)
        statusText = findViewById(R.id.statusText)
        modelStatusText = findViewById(R.id.modelStatusText)
        btnToggleModel = findViewById(R.id.btnDownloadLarge)
        checkShowCallButtons = findViewById(R.id.checkShowCallButtons)
        checkShutdownEnabled = findViewById(R.id.checkShutdownEnabled)
        radioLayout = findViewById(R.id.radioLayout)
        radioVertical = findViewById(R.id.radioVertical)
        radioHorizontal = findViewById(R.id.radioHorizontal)

        // Заполняем поля
        editActivation?.setText(settings.activationWord)
        editConfirm?.setText(settings.confirmWord)
        editShutdown?.setText(settings.shutdownWord)
        checkShowCallButtons.isChecked = settings.showCallButtons
        checkShutdownEnabled.isChecked = settings.shutdownEnabled

        if (settings.callButtonsLayout == 1) {
            radioHorizontal.isChecked = true
        } else {
            radioVertical.isChecked = true
        }

        // Кнопки записи слов
        findViewById<Button>(R.id.btnRecordActivation).setOnClickListener { startRecordingFor("activation") }
        findViewById<Button>(R.id.btnRecordConfirm).setOnClickListener { startRecordingFor("confirm") }
        findViewById<Button>(R.id.btnRecordShutdown).setOnClickListener { startRecordingFor("shutdown") }

        // Сохранить
        btnSave.setOnClickListener {
            settings.activationWord = editActivation?.text.toString()
            settings.confirmWord = editConfirm?.text.toString()
            settings.shutdownWord = editShutdown?.text.toString()
            settings.showCallButtons = checkShowCallButtons.isChecked
            settings.shutdownEnabled = checkShutdownEnabled.isChecked
            settings.callButtonsLayout = if (radioHorizontal.isChecked) 1 else 0
            Toast.makeText(this, "Сохранено. Перезапустите помощника.", Toast.LENGTH_LONG).show()
            finish()
        }

        btnBack.setOnClickListener { finish() }

        // Кнопка загрузки модели
        btnToggleModel.setOnClickListener {
            if (modelDownloader.isModelReady()) {
                // Удалить модель
                modelDownloader.getModelDir().deleteRecursively()
                updateModelUI()
                Toast.makeText(this, "Модель удалена. Перезапустите помощника.", Toast.LENGTH_LONG).show()
            } else {
                // Скачать модель
                btnToggleModel.isEnabled = false
                btnToggleModel.text = "ЗАГРУЗКА..."
                modelStatusText.text = "Начинаю загрузку..."
                modelDownloader.downloadAndUnzip(
                    onProgress = { msg -> runOnUiThread { modelStatusText.text = msg } },
                    onComplete = { success ->
                        runOnUiThread {
                            if (success) {
                                updateModelUI()
                                Toast.makeText(this@SettingsActivity, "Модель установлена. Перезапустите помощника.", Toast.LENGTH_LONG).show()
                            } else {
                                btnToggleModel.isEnabled = true
                                btnToggleModel.text = "ПОВТОРИТЬ ЗАГРУЗКУ"
                                modelStatusText.text = "Ошибка загрузки."
                            }
                        }
                    }
                )
            }
        }

        updateModelUI()
    }

    private fun updateModelUI() {
        if (modelDownloader.isModelReady()) {
            modelStatusText.text = "Модель: улучшенная (загружена)"
            btnToggleModel.text = "УДАЛИТЬ МОДЕЛЬ"
        } else {
            modelStatusText.text = "Модель: стандартная (встроенная)\nУлучшенная модель не загружена."
            btnToggleModel.text = "СКАЧАТЬ УЛУЧШЕННУЮ МОДЕЛЬ"
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
