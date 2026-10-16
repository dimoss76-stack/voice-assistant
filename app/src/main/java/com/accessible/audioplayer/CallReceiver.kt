package com.accessible.audioplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        val service = VoiceService.instance
        Log.e("MYVOSK", "CallReceiver state=$state, service=${service != null}")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                val name = if (number.isNullOrEmpty()) {
                    "Неизвестный номер"
                } else {
                    getContactName(context, number) ?: "Неизвестный номер"
                }
                Log.e("MYVOSK", "CallReceiver RINGING: $name")

                // Голосовое управление
                service?.onIncomingCall(name)

                // Кнопки на экране (если включено)
                val settings = VoiceSettings(context)
                if (settings.showCallButtons) {
                    try {
                        // Закрыть старую CallActivity, если открыта
                        val old = CallActivity.instance
                        if (old != null) {
                            Log.e("MYVOSK", "CallReceiver: закрываю старую CallActivity")
                            old.finish()
                            CallActivity.instance = null
                        }

                        // Запустить новую
                        val callIntent = Intent(context, CallActivity::class.java).apply {
                            putExtra("caller_name", name)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(callIntent)
                        Log.e("MYVOSK", "CallReceiver: CallActivity запущена для $name")
                    } catch (e: Exception) {
                        Log.e("MYVOSK", "Ошибка запуска CallActivity: ${e.message}", e)
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.e("MYVOSK", "CallReceiver OFFHOOK")
                service?.onCallAnswered()
                // Закрыть CallActivity
                CallActivity.instance?.finish()
                CallActivity.instance = null
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.e("MYVOSK", "CallReceiver IDLE")
                service?.onCallEnded()
                // Закрыть CallActivity
                CallActivity.instance?.finish()
                CallActivity.instance = null
            }
        }
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка чтения контактов: ${e.message}", e)
        }
        return null
    }
}
