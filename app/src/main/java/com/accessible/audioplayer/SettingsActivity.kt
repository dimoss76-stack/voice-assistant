package com.accessible.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

    private var editActivation: EditText? = null
    private var editConfirm: EditText? = null
    private var editTime: EditText? = null
    private var editDate: EditText? = null
    private var editShutdown: EditText? = null

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
    }

    private fun startRecordingFor(target: EditText?) {
        if (target == null) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
            return
        }

        currentTargetField = target
        Log.e("MYVOSK", "Старт Vosk-записи для поля")

        // 1. Останавливаем VoiceService, чтобы освободить микрофон
        statusText.text = "Останавливаю помощника…"
        stopService(Intent(this, VoiceService::class.java))

        // 2. Небольшая пауза, чтобы микрофон освободился
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startVoskRecording()
        }, 500)
    }

    private fun startVoskRecording() {
        statusText.text = "Говорите…"

        val recorder = VoiceRecorder(
            context = this,
            onResult = { text ->
                runOnUiThread {
                    currentTargetField?.setText(text)
                    statusText.text = "Распознано: $text"
                }
            },
            onStatus = { msg ->
                runOnUiThread { statusText.text = msg }
            },
            onFinished = {
                runOnUiThread {
                    // 3. Перезапускаем VoiceService
                    val intent = Intent(this, VoiceService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Log.e("MYVOSK", "VoiceService перезапущен после записи")
                }
            }
        )
        recorder.startRecording(5000L)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
