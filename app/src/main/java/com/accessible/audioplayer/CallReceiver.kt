package com.accessible.audioplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                Log.e("MYVOSK", "Входящий звонок с номера: $incomingNumber")

                val callerName = if (incomingNumber.isNullOrEmpty()) {
                    "Неизвестный номер"
                } else {
                    getContactName(context, incomingNumber) ?: "Неизвестный номер"
                }
                Log.e("MYVOSK", "Определено имя: $callerName")

                val serviceIntent = Intent(context, VoiceService::class.java).apply {
                    action = "INCOMING_CALL"
                    putExtra("CALLER_NAME", callerName)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.e("MYVOSK", "Звонок принят (OFFHOOK)")
                val serviceIntent = Intent(context, VoiceService::class.java).apply {
                    action = "CALL_ANSWERED"
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.e("MYVOSK", "Звонок завершён (IDLE)")
                val serviceIntent = Intent(context, VoiceService::class.java).apply {
                    action = "CALL_ENDED"
                }
                ContextCompat.startForegroundService(context, serviceIntent)
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
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка чтения контактов: ${e.message}", e)
        }
        return null
    }
}
