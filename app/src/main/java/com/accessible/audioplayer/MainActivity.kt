package com.accessible.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("MYVOSK", "M0. MainActivity onCreate начался")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 200, 60, 60)
        }

        statusText = TextView(this).apply {
            text = "Проверяю разрешения..."
            textSize = 22f
            setPadding(0, 0, 0, 60)
        }
        layout.addView(statusText)

        val btnHelp = Button(this).apply {
            text = "ПОМОЩЬ"
            textSize = 22f
            setPadding(30, 60, 30, 60)
        }
        btnHelp.setOnClickListener {
            startActivity(Intent(this@MainActivity, HelpActivity::class.java))
        }
        layout.addView(btnHelp)

        val btnSettings = Button(this).apply {
            text = "НАСТРОЙКИ"
            textSize = 22f
            setPadding(30, 60, 30, 60)
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        layout.addView(btnSettings)

        val spacer = TextView(this).apply { text = ""; height = 100 }
        layout.addView(spacer)

        val btnExit = Button(this).apply {
            text = "ВЫХОД"
            textSize = 14f
            setPadding(20, 20, 20, 20)
        }
        btnExit.setOnClickListener {
            stopService(Intent(this@MainActivity, VoiceService::class.java))
            finishAffinity()
            System.exit(0)
        }
        layout.addView(btnExit)

        setContentView(layout)
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val extraPerms = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE
        )
        for (perm in extraPerms) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(perm)
            }
        }

        if (permissions.isNotEmpty()) {
            statusText.text = "Запрашиваю разрешения..."
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        } else {
            statusText.text = "Помощник работает\nВерсия 2026-10-14-v18 (Звонки)"
            startVoiceService()
        }
    }

    private fun startVoiceService() {
        statusText.text = "Помощник работает\nВерсия 2026-10-14-v18 (Звонки)"
        val intent = Intent(this, VoiceService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            statusText.text = "Ошибка: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            statusText.text = "Помощник работает\nВерсия 2026-10-14-v18 (Звонки)"
            startVoiceService()
        } else {
            statusText.text = "Разрешение на микрофон НЕ выдано"
        }
    }
}
