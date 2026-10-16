package com.accessible.audioplayer

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CallActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var instance: CallActivity? = null
        const val AUTO_CLOSE_MS = 30000L
    }

    private var callerName: String = "Неизвестный"
    private val handler = Handler(Looper.getMainLooper())
    private val autoCloseRunnable = Runnable {
        Log.e("MYVOSK", "CallActivity: автозакрытие через 30 сек")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Логи
        Log.e("MYVOSK", "CallActivity onCreate НАЧАЛО")

        // Показывать на заблокированном экране
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Выбор layout'а по настройке
        val settings = VoiceSettings(this)
        val layoutId = if (settings.callButtonsLayout == 1) {
            R.layout.activity_call_horizontal
        } else {
            R.layout.activity_call
        }
        setContentView(layoutId)
        Log.e("MYVOSK", "CallActivity onCreate: layout=${settings.callButtonsLayout}")

        callerName = intent.getStringExtra("caller_name") ?: "Неизвестный"
        Log.e("MYVOSK", "CallActivity onCreate: caller=$callerName")

        val nameView = findViewById<TextView>(R.id.callerName)
        nameView.text = "Звонит\n$callerName"

        val btnAnswer = findViewById<Button>(R.id.btnAnswer)
        val btnReject = findViewById<Button>(R.id.btnReject)

        btnAnswer.setOnClickListener {
            Log.e("MYVOSK", "CallActivity: нажата ОТВЕТИТЬ")
            vibrateShort()
            answerCall()
            finish()
        }

        btnReject.setOnClickListener {
            Log.e("MYVOSK", "CallActivity: нажата СБРОСИТЬ")
            vibrateShort()
            rejectCall()
            finish()
        }

        // Автозакрытие через 30 секунд
        handler.removeCallbacks(autoCloseRunnable)
        handler.postDelayed(autoCloseRunnable, AUTO_CLOSE_MS)

        Log.e("MYVOSK", "CallActivity onCreate КОНЕЦ")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.e("MYVOSK", "CallActivity onNewIntent")
        // Обновляем имя звонящего
        callerName = intent?.getStringExtra("caller_name") ?: "Неизвестный"
        val nameView = findViewById<TextView>(R.id.callerName)
        nameView?.text = "Звонит\n$callerName"
        Log.e("MYVOSK", "CallActivity onNewIntent: caller=$callerName")
    }

    override fun onDestroy() {
        Log.e("MYVOSK", "CallActivity onDestroy")
        instance = null
        handler.removeCallbacks(autoCloseRunnable)
        super.onDestroy()
    }

    private fun vibrateShort() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(80)
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка вибрации: ${e.message}", e)
        }
    }

    private fun answerCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                tm.acceptRingingCall()
                Log.e("MYVOSK", "CallActivity: принял звонок")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка приёма: ${e.message}", e)
        }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = getSystemService(TELECOM_SERVICE) as TelecomManager
                tm.endCall()
                Log.e("MYVOSK", "CallActivity: отклонил звонок")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка отклонения: ${e.message}", e)
        }
    }
}
