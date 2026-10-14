override fun onResult(hypothesis: String?) {
    if (recordingMode) return
    if (hypothesis == null) return
    val text = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"").trim().lowercase()
    if (text.isEmpty()) return
    if (System.currentTimeMillis() < ttsSpeakingUntil) return

    Log.e("MYVOSK", "Распознано: '$text'")

    val activation = settings.activationWord
    val confirm = settings.confirmWord
    val shutdownWord = settings.shutdownWord

    // Голосовое выключение — без активации
    if (text.contains(shutdownWord)) {
        speakOut("Выключаюсь")
        handler.postDelayed({ stopSelf(); System.exit(0) }, 1500)
        return
    }

    // Ответ на звонок
    if (awaitingAnswer) {
        val words = text.split(" ", ",", ".", "!", "?")
        if (words.contains("да") || words.contains("ответить") || words.contains("ответь")) {
            speakOut("Отвечаю"); acceptCall(); awaitingAnswer = false; return
        }
        if (words.contains("нет") || words.contains("сбрось") || words.contains("отклони")) {
            speakOut("Отклоняю"); rejectCall(); awaitingAnswer = false; return
        }
        return
    }

    // Активация
    if (!isActivated) {
        if (text.contains(activation)) {
            if (System.currentTimeMillis() - lastActivationTime < 3000) return
            lastActivationTime = System.currentTimeMillis()
            isActivated = true
            val cmd = text.replace(activation, "").trim()
            if (cmd.isNotEmpty()) {
                executeCommand(cmd); isActivated = false
            } else {
                speakOut(confirm)
                handler.removeCallbacks(resetActivation)
                handler.postDelayed(resetActivation, 15000L)
            }
        }
    } else {
        if (text == activation) return
        executeCommand(text)
        handler.removeCallbacks(resetActivation)
        isActivated = false
    }
}
