package com.accessible.audioplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: VoiceSettings
    private lateinit var editActivation: EditText
    private lateinit var editConfirm: EditText
    private lateinit var editTime: EditText
    private lateinit var editDate: EditText
    private lateinit var editShutdown: EditText
    private lateinit var checkShowCallButtons: CheckBox

    private var recordingField: String = ""

    private val wordReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
            val field = resultData.getString("FIELD") ?: return
            val word = resultData.getString("WORD") ?: ""
            Log.e("MYVOSK", "Получено слово для '$field': '$word'")
            runOnUiThread {
                when (field) {
                    "activation" -> editActivation.setText(word)
                    "confirm" -> editConfirm.setText(word)
                    "time" -> editTime.setText(word)
                    "date" -> editDate.setText(word)
                    "shutdown" -> editShutdown.setText(word)
                }
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
        checkShowCallButtons = findViewById(R.id.checkShowCallButtons)

        editActivation.setText(settings.activationWord)
        editConfirm.setText(settings.confirmWord)
        editTime.setText(settings.timeWord)
        editDate.setText(settings.dateWord)
        editShutdown.setText(settings.shutdownWord)
        checkShowCallButtons.isChecked = settings.showCallButtons

        findViewById<Button>(R.id.btnRecordActivation).setOnClickListener {
            startWordRecording("activation")
        }
        findViewById<Button>(R.id.btnRecordConfirm).setOnClickListener {
            startWordRecording("confirm")
        }
        findViewById<Button>(R.id.btnRecordTime).setOnClickListener {
            startWordRecording("time")
        }
        findViewById<Button>(R.id.btnRecordDate).setOnClickListener {
            startWordRecording("date")
        }
        findViewById<Button>(R.id.btnRecordShutdown).setOnClickListener {
            startWordRecording("shutdown")
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            settings.activationWord = editActivation.text.toString()
            settings.confirmWord = editConfirm.text.toString()
            settings.timeWord = editTime.text.toString()
            settings.dateWord = editDate.text.toString()
            settings.shutdownWord = editShutdown.text.toString()
            settings.showCallButtons = checkShowCallButtons.isChecked

            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            Log.e("MYVOSK", "Настройки сохранены")
            finish()
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun startWordRecording(field: String) {
        recordingField = field
        val intent = Intent(this, VoiceService::class.java).apply {
            action = "RECORD_WORD"
            putExtra("FIELD", field)
            putExtra("RECEIVER", wordReceiver)
        }
        startService(intent)
    }
}
