package com.accessible.audioplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("MYVOSK", "M0. MainActivity onCreate начался")

        statusText = TextView(this).apply {
            text = "Приложение запущено.\nПроверяю разрешения..."
            textSize = 22f
            setPadding(60, 300, 60, 60)
        }
        setContentView(statusText)

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

        if (permissions.isNotEmpty()) {
            Log.e("MYVOSK", "M1. Нет разрешений, запрашиваю: $permissions")
            statusText.text = "Приложение запущено.\nЗапрашиваю разрешения..."
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        } else {
            Log.e("MYVOSK", "M2. Все разрешения есть, запускаю сервис")
            startVoiceService()
        }
    }

    private fun startVoiceService() {
        Log.e("MYVOSK", "M3. startVoiceService() вызван")
        statusText.text = "Разрешения есть.\nЗапускаю сервис..."
        val intent = Intent(this, VoiceService::class.java)
        try {
            startService(intent)
            Log.e("MYVOSK", "M4. Сервис запущен командой")
        } catch (e: Exception) {
            Log.e("MYVOSK", "M5. Ошибка запуска сервиса: ${e.message}", e)
            statusText.text = "Ошибка запуска сервиса:\n${e.message}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.e("MYVOSK", "M6. onRequestPermissionsResult, requestCode=$requestCode")

        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (micGranted) {
            Log.e("MYVOSK", "M7. Разрешение на микрофон выдано")
            startVoiceService()
        } else {
            Log.e("MYVOSK", "M8. Разрешение на микрофон НЕ выдано")
            statusText.text = "Разрешение на микрофон НЕ выдано.\nПриложение не сможет слушать команды."
        }
    }
}
