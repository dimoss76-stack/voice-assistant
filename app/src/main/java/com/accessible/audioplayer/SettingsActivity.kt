package com.accessible.audioplayer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
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

    private var currentFieldName: String = ""

    private val wordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "VOICE_WORD_RECORDED") return
            val field = intent.getStringExtra("FIELD") ?: return
            val word = intent.getStringExtra("WORD") ?: ""

            Log.e("MYVOSK", "Получен Broadcast: поле=$field, слово='$word'")

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
                statusText.text = "Не расслышал, попробуйте ещё"
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

        // Регистрируем приём Broadcast
        val filter = IntentFilter("VOICE_WORD_RECORDED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wordReceiver, filter)
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.e("MYVOSK", "Отправлена команда RECORD_WORD для поля: $fieldName")
    }

    override fun onDestroy() {
        try { unregisterReceiver(wordReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
