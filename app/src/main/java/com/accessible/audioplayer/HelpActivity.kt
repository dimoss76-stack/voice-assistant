package com.accessible.audioplayer

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = VoiceSettings(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
        }

        val text = TextView(this).apply {
            textSize = 20f
            text = """
                КАК ПОЛЬЗОВАТЬСЯ ПОМОЩНИКОМ

                1. АКТИВАЦИЯ
                Скажите «${settings.activationWord}».
                Программа ответит «${settings.confirmWord}» и будет ждать команду 15 секунд.

                2. ВРЕМЯ
                Скажите «${settings.timeWord}» — узнаете время.
                Можно через активацию: «${settings.activationWord}, сколько времени».

                3. ДАТА
                Скажите «${settings.dateWord}» — узнаете сегодняшнюю дату.
                Или: «${settings.activationWord}, какой сегодня день».

                4. ЗВОНКИ
                Когда звонит телефон, программа скажет:
                «Звонит Имя Фамилия. Ответить?»
                Скажите «да» — ответит, «нет» — сбросит.

                5. ВЫКЛЮЧЕНИЕ
                Скажите «${settings.shutdownWord}» — помощник выключится.
                Чтобы включить снова — откройте приложение.

                НАСТРОЙКИ
                Через кнопку «НАСТРОЙКИ» зрячий помощник может:
                • изменить ключевые слова;
                • ввести слово вручную или записать голосом.

                Если что-то непонятно — попросите помощи у близких.
            """.trimIndent()
        }
        layout.addView(text)

        val btnBack = Button(this).apply {
            text = "НАЗАД"
            textSize = 20f
            setPadding(30, 50, 30, 50)
        }
        btnBack.setOnClickListener { finish() }
        layout.addView(btnBack)

        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)
    }
}
