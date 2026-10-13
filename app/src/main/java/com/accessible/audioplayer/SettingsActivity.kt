package com.accessible.audioplayer

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val settings = VoiceSettings(this)

        val editActivation = findViewById<EditText>(R.id.editActivation)
        val editConfirm = findViewById<EditText>(R.id.editConfirm)
        val editTime = findViewById<EditText>(R.id.editTime)
        val editDate = findViewById<EditText>(R.id.editDate)
        val editShutdown = findViewById<EditText>(R.id.editShutdown)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnBack = findViewById<Button>(R.id.btnBack)

        // Показываем текущие значения
        editActivation.setText(settings.activationWord)
        editConfirm.setText(settings.confirmWord)
        editTime.setText(settings.timeWord)
        editDate.setText(settings.dateWord)
        editShutdown.setText(settings.shutdownWord)

        btnSave.setOnClickListener {
            settings.activationWord = editActivation.text.toString()
            settings.confirmWord = editConfirm.text.toString()
            settings.timeWord = editTime.text.toString()
            settings.dateWord = editDate.text.toString()
            settings.shutdownWord = editShutdown.text.toString()

            Toast.makeText(this, "Сохранено. Перезапустите помощника.", Toast.LENGTH_LONG).show()
            finish()
        }

        btnBack.setOnClickListener {
            finish()
        }
    }
}
