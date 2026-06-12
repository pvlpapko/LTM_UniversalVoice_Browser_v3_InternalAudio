# LTM UniversalVoice Browser v3 — Internal Audio

Android-браузер на GeckoView с внутренним голосовым переводом видео.

## Что изменено в v3

- Перевод больше не идёт через микрофон.
- Добавлен внутренний захват звука через Android MediaProjection + AudioPlaybackCapture.
- Видео не трогается: нет `captureStream()`, нет вмешательства в HTML5 video, поэтому не должно быть чёрного экрана из-за самого переводчика.
- Приложение работает как обычный браузер для любых сайтов.
- Панели обработаны через system insets/decorFitsSystemWindows, чтобы кнопки не уходили под статус-бар.
- Добавлен foreground service `mediaProjection` для Android 14+.
- Добавлен backend `backend-openai`, который реально распознаёт аудио и переводит его.
- GitHub Actions собирает APK и AAB.

## Как работает перевод

```text
Видео на сайте → системный звук Android → AudioPlaybackCapture → WAV-чанки → backend /translate-audio → перевод → Android TextToSpeech
```

## Важное ограничение Android

Внутренний аудиозахват доступен на Android 10+ и зависит от того, разрешает ли источник звука захват. Для видео внутри этого браузера шанс выше, потому что звук идёт из самого приложения, но DRM/защищённые плееры или отдельные ограничения Android всё равно могут мешать.

## Backend

В проекте есть готовый backend:

```bash
cd backend-openai
npm install
copy .env.example .env
npm start
```

В `.env` нужно вписать `OPENAI_API_KEY`.

В приложении откройте **⚙ Настройки** и укажите:

```text
http://IP_КОМПЬЮТЕРА:8787/translate-audio
```

Для Android Emulator:

```text
http://10.0.2.2:8787/translate-audio
```

## Сборка через GitHub Actions

1. Создайте репозиторий GitHub.
2. Загрузите содержимое архива в корень репозитория.
3. Откройте **Actions**.
4. Запустите **Build APK and AAB**.
5. Скачайте artifact `ltm-universalvoice-browser-apk-aab`.

Внутри должны быть:

- `app-debug.apk`
- `app-debug.aab`
- `app-release.aab` без подписи

## Локальная сборка

```bash
gradle :app:assembleDebug :app:bundleDebug :app:bundleRelease --stacktrace
```

## Расширения

- встроенный LTM Shield;
- Mozilla Add-ons;
- установка подписанных `.xpi` по ссылке.
