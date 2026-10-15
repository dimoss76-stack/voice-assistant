package com.accessible.audioplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

class ContactCaller(private val context: Context) {

    data class Contact(val name: String, val number: String)

    // Поиск контактов по фразе (например, "Дима Блохов")
    fun findContacts(query: String): List<Contact> {
        Log.e("MYVOSK", "Поиск контактов: '$query'")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("MYVOSK", "Нет разрешения READ_CONTACTS")
            return emptyList()
        }

        val result = mutableListOf<Contact>()

        val words = query.lowercase().split(" ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val cyrillicWords = words.filter { word ->
            word.any { ch -> ch in 'а'..'я' || ch == 'ё' }
        }

        if (cyrillicWords.isEmpty()) {
            Log.e("MYVOSK", "Все слова на латинице — игнорирую")
            return emptyList()
        }

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = mutableSetOf<String>()

                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val number = c.getString(numIdx) ?: continue
                    val lowerName = name.lowercase()

                    val matches = cyrillicWords.all { lowerName.contains(it) }
                    if (matches) {
                        if (seen.add(name + number)) {
                            result.add(Contact(name, number))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка поиска контактов: ${e.message}", e)
        }

        Log.e("MYVOSK", "Найдено контактов: ${result.size}")
        return result
    }

    // Все имена контактов (для словаря Vosk)
    fun getAllContactNames(): List<String> {
        val names = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("MYVOSK", "Нет разрешения READ_CONTACTS для getAllContactNames")
            return emptyList()
        }

        try {
            val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME)
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection, null, null, null
            )?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val fullName = c.getString(nameIdx) ?: continue
                    val firstWord = fullName.split(" ")
                        .firstOrNull()?.trim()?.lowercase() ?: continue
                    if (firstWord.length >= 2 &&
                        firstWord.any { it in 'а'..'я' || it == 'ё' }) {
                        names.add(firstWord)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка чтения имён: ${e.message}", e)
        }

        val unique = names.distinct()
        Log.e("MYVOSK", "Всего уникальных имён: ${unique.size}")
        return unique
    }

    // Позвонить по номеру — через TelecomManager (обход BAL на Android 14+)
    fun callNumber(number: String): Boolean {
        // Очищаем номер от всего, кроме цифр и +
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        Log.e("MYVOSK", "callNumber: очищенный номер = $cleanNumber")

        // Проверка разрешения CALL_PHONE
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("MYVOSK", "Нет разрешения CALL_PHONE!")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Современный способ — обходит Background Activity Launch restriction
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val uri = Uri.fromParts("tel", cleanNumber, null)
                val extras = Bundle().apply {
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
                }
                tm.placeCall(uri, extras)
                Log.e("MYVOSK", "Звоню через TelecomManager: $cleanNumber (громкая связь)")
            } else {
                // Fallback для старых Android
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$cleanNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(android.telecom.TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
                }
                context.startActivity(intent)
                Log.e("MYVOSK", "Звоню через ACTION_CALL: $cleanNumber")
            }
            true
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка звонка: ${e.message}", e)
            // Запасной вариант — ACTION_DIAL (открывает звонилку)
            try {
                val fallback = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$cleanNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                Log.e("MYVOSK", "Fallback: открываю звонилку (ACTION_DIAL)")
                true
            } catch (e2: Exception) {
                Log.e("MYVOSK", "Ошибка fallback: ${e2.message}", e2)
                false
            }
        }
    }
}
