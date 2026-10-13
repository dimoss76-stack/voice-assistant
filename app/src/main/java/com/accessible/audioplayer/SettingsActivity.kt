package com.accessible.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: VoiceSettings
    private lateinit var statusText: TextView

    private var editActivation: EditText? = null
    private var editConfirm: EditText? = null
    private var editTime: EditText? = null
    private var editDate: EditText? = null
    private var editShutdown: EditText? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentTargetField: EditText? = null

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

        editActivation?.setText(settings.activationWord)
        editConfirm?.setText(settings.confirmWord)
        editTime?.setText(settings.timeWord)
        editDate?.setText(settings.dateWord)
        editShutdown?.setText(settings.shutdownWord)

        // Кнопки записи
        findViewById<Button>(R.id.btnRecordActivation).setOnClickListener {
            startRecordingFor(editActivation)
        }
        findViewById<Button>(R.id.btnRecordConfirm).setOnClickListener {
            startRecordingFor(editConfirm)
        }
        findViewById<Button>(R.id.btnRecordTime).setOnClickListener {
            startRecordingFor(editTime)
        }
        findViewById<Button>(R.id.btnRecordDate).setOnClickListener {
            startRecordingFor(editDate)
        }
        findViewById<Button>(R.id.btnRecordShutdown).setOnClickListener {
            startRecordingFor(editShutdown)
        }

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

        // Инициализируем распознаватель
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createListener())
            }
        }
    }

    private fun startRecordingFor(target: EditText?) {
        if (target == null) return

        if (speechRecognizer == null) {
            Toast.makeText(this, "Распознавание речи недоступно на этом телефоне", Toast.LENGTH_LONG).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
            return
        }

        currentTargetField = target
        statusText.text = "Говорите…"

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка startListening: ${e.message}", e)
            statusText.text = "Ошибка: ${e.message}"
        }
    }

    private fun createListener() = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.e("MYVOSK", "Запись: готов слушать")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            statusText.text = "Обрабатываю…"
        }
        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал, попробуйте ещё"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Тишина. Попробуйте ещё"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
                else -> "Ошибка распознавания ($error)"
            }
            statusText.text = msg
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0].lowercase(Locale("ru")).trim()
                currentTargetField?.setText(text)
                statusText.text = "Распознано: $text"
            } else {
                statusText.text = "Не расслышал"
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}
