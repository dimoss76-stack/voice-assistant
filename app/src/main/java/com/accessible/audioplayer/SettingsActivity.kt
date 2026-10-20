package com.accessible.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
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
    private lateinit var btnDownloadModel: Button
    private lateinit var progressBar: ProgressBar
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
        btnToggleModel = findViewById(R.id.btnDownloadLarge) // Это кнопка "Скачать"
        btnDownloadModel = findViewById(R.id.btnDownloadLarge) // Используем тот же ID
        progressBar = findViewById(R.id.progressBar)
        checkShowCallButtons = findViewById(R.id.checkShowCallButtons)
        checkShutdownEnabled = findViewById(R.id.checkShutdownEnabled)
        radioLayout = findViewById(R.id.radioLayout)
        radioVertical = findViewById(R.id.radioVertical)
        radioHorizontal = findViewById(R.id.radioHorizontal)

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

        findViewById<Button>(R.id.btnRecordActivation).setOnClickListener { startRecordingFor("activation") }
        findViewById<Button>(R.id.btnRecordConfirm).setOnClickListener { startRecordingFor("confirm") }
        findViewById<Button>(R.id.btnRecordShutdown).setOnClickListener { startRecordingFor("shutdown") }

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

        // Обработчик кнопки загрузки/удаления модели
        btnDownloadModel.setOnClickListener {
            if (modelDownloader.isModelReady()) {
                // Если модель скачана — предлагаем переключиться или удалить
                if (settings.modelMode == 0) {
                    // Включаем streaming
                    settings.modelMode = 1
                    updateModelUI()
                    Toast.makeText(this, "Включена streaming-модель. Перезапустите помощника.", Toast.LENGTH_LONG).show()
                } else {
                    // Выключаем streaming
                    settings.modelMode = 0
                    updateModelUI()
                    Toast.makeText(this, "Включена стандартная модель. Перезапустите помощника.", Toast.LENGTH_LONG).show()
                }
            } else {
                // Модели нет — скачиваем
                btnDownloadModel.isEnabled = false
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                modelStatusText.text = "Загрузка: 0%"

                modelDownloader.downloadAndUnzip(
                    onProgress = { msg ->
                        runOnUiThread { modelStatusText.text = msg }
                    },
                    onProgressPercent = { percent ->
                        runOnUiThread {
                            progressBar.progress = percent
                            modelStatusText.text = "Загрузка: $percent%"
                        }
                    },
                    onComplete = { success ->
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            if (success) {
                                // После успешной загрузки автоматически включаем streaming
                                settings.modelMode = 1
                                updateModelUI()
                                Toast.makeText(this@SettingsActivity, "Модель установлена. Перезапустите помощника.", Toast.LENGTH_LONG).show()
                            } else {
                                btnDownloadModel.isEnabled = true
                                btnDownloadModel.text = "ПОВТОРИТЬ ЗАГРУЗКУ"
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
            if (settings.modelMode == 1) {
                modelStatusText.text = "Модель: streaming (569 МБ, используется)"
                btnDownloadModel.text = "ВЕРНУТЬСЯ К СТАНДАРТНОЙ"
            } else {
                modelStatusText.text = "Модель: стандартная (45 МБ)\nДоступна streaming-модель (569 МБ)"
                btnDownloadModel.text = "ИСПОЛЬЗОВАТЬ STREAMING-МОДЕЛЬ"
            }
        } else {
            modelStatusText.text = "Модель: стандартная (45 МБ)\nStreaming-модель не загружена."
            btnDownloadModel.text = "СКАЧАТЬ STREAMING-МОДЕЛЬ"
        }
        btnDownloadModel.isEnabled = true
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
