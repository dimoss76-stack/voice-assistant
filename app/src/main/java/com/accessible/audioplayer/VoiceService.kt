private fun startListeningForHangup() {
    try {
        Log.e("MYVOSK", "Переключаюсь на грамматику 'завершить звонок'")
        speechService?.stop()
        speechService?.shutdown()
        speechService = null

        model?.let { m ->
            val grammar = """[
                "завершить этот звонок",
                "завершить звонок",
                "положить трубку"
            ]"""
            val rec = Recognizer(m, 16000.0f, grammar)
            speechService = SpeechService(rec, 16000.0f)
            speechService?.startListening(this)
            Log.e("MYVOSK", "Слушаю только 'завершить этот звонок'")
        }
    } catch (e: Exception) {
        Log.e("MYVOSK", "Ошибка startListeningForHangup: ${e.message}", e)
    }
}
