package com.accessible.audioplayer

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class CallOverlay(private val context: Context) {

    companion object {
        @Volatile
        var instance: CallOverlay? = null
        const val AUTO_CLOSE_MS = 30000L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoCloseRunnable = Runnable {
        Log.e("MYVOSK", "CallOverlay: автозакрытие через 30 сек")
        hide()
    }

    fun show(callerName: String) {
        if (overlayView != null) {
            Log.e("MYVOSK", "CallOverlay уже показан")
            return
        }

        instance = this
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Inflate layout
        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.activity_call, null)

        val nameView = overlayView?.findViewById<TextView>(R.id.callerName)
        nameView?.text = "Звонит\n$callerName"

        val btnAnswer = overlayView?.findViewById<Button>(R.id.btnAnswer)
        val btnReject = overlayView?.findViewById<Button>(R.id.btnReject)

        btnAnswer?.setOnClickListener {
            Log.e("MYVOSK", "CallOverlay: нажата ОТВЕТИТЬ")
            vibrateShort()
            answerCall()
            hide()
        }

        btnReject?.setOnClickListener {
            Log.e("MYVOSK", "CallOverlay: нажата СБРОСИТЬ")
            vibrateShort()
            rejectCall()
            hide()
        }

        // Параметры окна — на весь экран
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager?.addView(overlayView, params)
            Log.e("MYVOSK", "CallOverlay показан для $callerName")

            // Автозакрытие
            handler.postDelayed(autoCloseRunnable, AUTO_CLOSE_MS)
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка показа CallOverlay: ${e.message}", e)
        }
    }

    fun hide() {
        handler.removeCallbacks(autoCloseRunnable)
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                Log.e("MYVOSK", "CallOverlay скрыт")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка скрытия CallOverlay: ${e.message}", e)
        }
        overlayView = null
        instance = null
    }

    private fun vibrateShort() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
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
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.acceptRingingCall()
                Log.e("MYVOSK", "CallOverlay: принял звонок")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка приёма: ${e.message}", e)
        }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.endCall()
                Log.e("MYVOSK", "CallOverlay: отклонил звонок")
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка отклонения: ${e.message}", e)
        }
    }
}
