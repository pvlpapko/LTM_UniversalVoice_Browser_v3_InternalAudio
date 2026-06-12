# LTM UniversalVoice Backend OpenAI

Backend для настоящего внутреннего голосового перевода.

Приложение захватывает внутренний звук Android через MediaProjection/AudioPlaybackCapture, режет его на WAV-чанки и отправляет сюда на `/translate-audio`.
Backend распознаёт речь через OpenAI Speech-to-Text и переводит текст в нужный язык. Android-приложение озвучивает ответ через TextToSpeech.

## Запуск

```bash
cd backend-openai
npm install
copy .env.example .env
# впишите OPENAI_API_KEY в .env
npm start
```

Потом в приложении укажите endpoint:

```text
http://IP_КОМПЬЮТЕРА:8787/translate-audio
```

Для эмулятора Android можно оставить:

```text
http://10.0.2.2:8787/translate-audio
```

## Ответ API

```json
{
  "text": "original transcript",
  "translatedText": "переведённый текст"
}
```
