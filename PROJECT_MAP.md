# КАРТА ПРОЕКТА «Голосовой Помощник» (на 23.10.2026)

## Главные файлы и их роли:
- `VoiceService.kt` — мозг. Управляет состояниями (активация, звонок, чтение контактов), таймингами, командами. Использует VoskEngine, TtsSpeaker, ContactCaller.
- `VoskEngine.kt` — уши. Загружает модель из assets (model-ru), создаёт Recognizer, методы: startListening, prepareListeningForNames, startListeningForHangup, startListeningForStop, recordSingleWord.
- `TtsSpeaker.kt` — рот. TTS + бипы. Управляет ignoreUntil (пауза после речи).
- `ContactCaller.kt` — поиск контактов, чтение списка, звонки через TelecomManager.
- `CallReceiver.kt` — ловит входящие звонки, передаёт в VoiceService.
- `CallActivity.kt` — экран с кнопками ОТВЕТИТЬ/СБРОСИТЬ (вертикальные/горизонтальные).
- `VoiceSettings.kt` — настройки (слова, галочки, расположение кнопок).
- `MainActivity.kt` — главный экран (ПОМОЩЬ, НАСТРОЙКИ, ВЫХОД).
- `SettingsActivity.kt` — настройки (без модели).
- `HelpActivity.kt` — справка (с рекомендацией по батарее).
- `AndroidManifest.xml` — разрешения, specialUse, иконка.

## Что работает:
- Vosk (маленькая модель 45 МБ) + грамматика.
- Звонки, время, дата, контакты.
- Кнопки при звонке (вертикальные/горизонтальные).
- Иконка и название «Голосовой Помощник v2.0».
- В RuStore на модерации.

## Что НЕ работает / удалено:
- Sherpa-ONNX (удалён, вызывал краши).
- ModelDownloader (удалён, большая модель не использовалась).
- CallOverlay (удалён, заменён на CallActivity).

## Ключевые связи:
- VoiceService → VoskEngine (распознавание).
- VoiceService → TtsSpeaker (озвучка).
- VoiceService → ContactCaller (звонки).
- CallReceiver → VoiceService.onIncomingCall.
- CallActivity открывается из CallReceiver.

## Правила работы:
- Работаем через сайт GitHub (не VS Code).
- После каждого коммита — сборка APK в Actions.
- Присылаем только изменяемые файлы, остальное не трогаем.
- Перед новой задачей вставляем эту карту в чат, чтобы не потерять логику.
