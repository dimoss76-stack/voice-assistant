package com.accessible.audioplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

class ContactCaller(private val context: Context) {

    data class Contact(val name: String, val number: String)

    // Поиск контактов по фразе (например, "Дима Блохов")
    fun findContacts(query: String): List<Contact> {
        Log.e("MYVOSK", "Поиск контактов: '$query'")

        // Проверка разрешения
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e("MYVOSK", "Нет разрешения READ_CONTACTS")
            return emptyList()
        }

        val result = mutableListOf<Contact>()

        // Разбиваем фразу на слова — каждое слово должно найтись в имени
        val words = query.lowercase().split(" ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Игнорируем слова на латинице
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

                    // Проверяем, что ВСЕ кириллические слова из запроса есть в имени
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
                    // Берём первое слово имени (например, "Дима" из "Дима Блохов")
                    val firstWord = fullName.split(" ")
                        .firstOrNull()?.trim()?.lowercase() ?: continue
                    // Фильтр: только кириллица, длина >= 2
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

    // Позвонить по номеру
    fun callNumber(number: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${number.replace(" ", "").replace("-", "")}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.e("MYVOSK", "Звоню по номеру $number")
            true
        } catch (e: Exception) {
            Log.e("MYVOSK", "Ошибка звонка: ${e.message}", e)
            false
        }
    }
}
