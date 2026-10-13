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
    private var gotResult = false
    private var savedText = ""

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

        findViewById<Button>(R.id.btnRecordActivation).setOnClickListener { startRecordingFor(editActivation) }
        findViewById<Button>(R.id.btnRecordConfirm).setOnClickListener { startRecordingFor(editConfirm) }
        findViewById<Button>(R.id.btnRecordTime).setOnClickListener { startRecordingFor(editTime) }
        findViewById<Button>(R.id.btnRecordDate).setOnClickListener { startRecordingFor(editDate) }
        findViewById<Button>(R.id.btnRecordShutdown).setOnClickListener { startRecordingFor(editShutdown) }

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

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createListener())
            }
            Log.e("MYVOSK", "SpeechRecognizer создан")
        } else {
            statusText.text = "Распознавание речи недоступно"
        }
    }

    private fun startRecordingFor(target: EditText?) {
        if (target == null) return

        if (speechRecognizer == null) {
            Toast.makeText(this, "Распознавание недоступно", Toast.LENGTH_LONG).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
            return
        }

        currentTargetField = target
        gotResult = false
        savedText = ""
        statusText.text = "Говорите…"
        Log.e("MYVOSK", "Старт записи")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)   // ← ВКЛЮЧАЕМ ЧАСТИЧНЫЕ РЕЗУЛЬТАТЫ
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
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
            Log.e("MYVOSK", "onReadyForSpeech")
        }
        override fun onBeginningOfSpeech() {
            Log.e("MYVOSK", "onBeginningOfSpeech")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.e("MYVOSK", "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            if (gotResult) {
                Log.e("MYVOSK", "onError после успеха — игнорирую")
                return
            }
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Ошибка аудио (1)"
                SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента (2)"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения (3)"
                SpeechRecognizer.ERROR_NETWORK -> "Проблема с сетью (4)"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Сеть не отвечает (5)"
                SpeechRecognizer.ERROR_NO_MATCH -> "Не расслышал (6)"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят (7)"
                SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера (8)"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Молчание (9)"
                else -> "Ошибка ($error)"
            }
            Log.e("MYVOSK", "onError: $msg")
            statusText.text = msg
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // ГЛАВНОЕ: сюда слово приходит РАНЬШЕ, чем onResults
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.e("MYVOSK", "onPartialResults: $partial")
            if (!partial.isNullOrEmpty()) {
                val text = partial[0].lowercase(Locale("ru")).trim()
                // Запоминаем, но пока не пишем в поле (слово ещё может уточниться)
                savedText = text
                statusText.text = "Слышу: $text…"
            }
        }

        override fun onResults(results: Bundle?) {
            gotResult = true
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            Log.e("MYVOSK", "onResults, matches = $matches")

            val finalText = when {
                !matches.isNullOrEmpty() -> matches[0].lowercase(Locale("ru")).trim()
                savedText.isNotEmpty() -> savedText   // ← ЗАЩИТА: если matches пусто, берём из onPartialResults
                else -> null
            }

            if (finalText != null) {
                currentTargetField?.setText(finalText)
                statusText.text = "Распознано: $finalText"
                Log.e("MYVOSK", "В поле записано: '$finalText'")
            } else {
                statusText.text = "Не расслышал"
                Log.e("MYVOSK", "Пустой результат")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}
