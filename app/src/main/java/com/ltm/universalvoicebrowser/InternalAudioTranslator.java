package com.ltm.universalvoicebrowser;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class InternalAudioTranslator {
    public interface StatusCallback {
        void onStatus(String text);
        void onOriginalText(String text);
        void onTranslatedText(String text);
    }

    private static final MediaType WAV = MediaType.parse("audio/wav");

    private final Context context;
    private final SettingsStore settings;
    private final StatusCallback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private Translator localTranslator;
    private String lastSpoken = "";
    private Semaphore pendingRequests = new Semaphore(2);

    public InternalAudioTranslator(Context context, SettingsStore settings, StatusCallback callback) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.callback = callback;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .build();
        initTts();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start(int resultCode, Intent projectionData) {
        if (running.get()) return;
        if (projectionData == null) {
            status("Нет разрешения Android на внутренний захват аудио");
            return;
        }
        if (settings.backendUrl().trim().isEmpty()) {
            status("Укажите backend endpoint в настройках, например http://IP:8787/translate-audio");
            return;
        }
        try {
            MediaProjectionManager manager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = manager == null ? null : manager.getMediaProjection(resultCode, projectionData);
            if (mediaProjection == null) {
                status("MediaProjection не выдан Android");
                return;
            }
            pendingRequests = new Semaphore(settings.maxPendingRequests());
            initLocalTranslatorIfNeeded();
            startAudioRecord();
            running.set(true);
            captureThread = new Thread(this::captureLoop, "ltm-internal-audio-capture");
            captureThread.start();
            status("Внутренний перевод запущен. Видео не трогаем, берём только системный звук браузера.");
        } catch (Throwable e) {
            stop();
            status("Ошибка запуска внутреннего аудио: " + shortError(e));
        }
    }

    public void stop() {
        running.set(false);
        try { if (audioRecord != null) audioRecord.stop(); } catch (Throwable ignored) {}
        try { if (audioRecord != null) audioRecord.release(); } catch (Throwable ignored) {}
        audioRecord = null;
        try { if (mediaProjection != null) mediaProjection.stop(); } catch (Throwable ignored) {}
        mediaProjection = null;
        try { if (localTranslator != null) localTranslator.close(); } catch (Throwable ignored) {}
        localTranslator = null;
        CaptureForegroundService.stop(context);
        status("Внутренний перевод остановлен");
    }

    public void shutdown() {
        stop();
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
        tts = null;
    }

    private void startAudioRecord() {
        int sampleRate = settings.captureSampleRate();
        int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            sampleRate = 44100;
            min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        }
        if (min <= 0) throw new IllegalStateException("AudioRecord buffer is not available");

        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

        int buffer = Math.max(min * 4, sampleRate * 2);
        audioRecord = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(buffer)
                .build();
    }

    private void captureLoop() {
        int sampleRate = settings.captureSampleRate();
        int bytesPerMs = Math.max(1, sampleRate * 2 / 1000);
        int targetBytes = Math.max(bytesPerMs * settings.chunkMs(), sampleRate * 2);
        byte[] readBuffer = new byte[4096];
        ByteArrayOutputStream chunk = new ByteArrayOutputStream(targetBytes + 4096);
        long chunkStarted = System.currentTimeMillis();
        long capturedBytes = 0;

        try {
            audioRecord.startRecording();
            while (running.get()) {
                int read = audioRecord.read(readBuffer, 0, readBuffer.length);
                if (read <= 0) continue;
                chunk.write(readBuffer, 0, read);
                capturedBytes += read;
                long elapsed = System.currentTimeMillis() - chunkStarted;
                if (chunk.size() >= targetBytes || elapsed >= settings.chunkMs() + 700L) {
                    byte[] pcm = chunk.toByteArray();
                    chunk.reset();
                    chunkStarted = System.currentTimeMillis();
                    double rms = rms16(pcm);
                    if (rms >= settings.silenceThreshold()) {
                        byte[] wav = makeWav(pcm, sampleRate);
                        sendAudio(wav, sampleRate, rms);
                    } else {
                        status("Тишина/слишком тихо: rms " + (int) rms);
                    }
                    if (capturedBytes > sampleRate * 2L * 60L) capturedBytes = 0;
                }
            }
        } catch (Throwable e) {
            if (running.get()) status("Ошибка захвата: " + shortError(e));
        }
    }

    private void sendAudio(byte[] wav, int sampleRate, double rms) {
        if (!pendingRequests.tryAcquire()) {
            status("Пропуск чанка: backend ещё обрабатывает предыдущий звук");
            return;
        }
        String endpoint = settings.backendUrl().trim();
        RequestBody audioBody = RequestBody.create(wav, WAV);
        MultipartBody.Builder multi = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "ltm-audio.wav", audioBody)
                .addFormDataPart("source", settings.sourceLang())
                .addFormDataPart("target", settings.targetLang())
                .addFormDataPart("sampleRate", String.valueOf(sampleRate))
                .addFormDataPart("rms", String.valueOf((int) rms));

        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .post(multi.build());
        String apiKey = settings.backendApiKey();
        if (!apiKey.isEmpty()) requestBuilder.header("Authorization", "Bearer " + apiKey);

        httpClient.newCall(requestBuilder.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                pendingRequests.release();
                status("Backend недоступен: " + shortError(e));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                pendingRequests.release();
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    status("Backend HTTP " + response.code() + ": " + crop(body));
                    return;
                }
                handleBackendResponse(body);
            }
        });
    }

    private void handleBackendResponse(String body) {
        try {
            JSONObject json = new JSONObject(body == null ? "{}" : body);
            String original = first(json, "text", "original", "transcript", "sourceText");
            String translated = first(json, "translatedText", "translated_text", "translation", "targetText");
            if (!original.isEmpty()) originalText(original);
            if (!translated.isEmpty()) {
                speak(translated);
            } else if (!original.isEmpty() && settings.useLocalTextTranslateFallback()) {
                translateLocalAndSpeak(original);
            } else if (!original.isEmpty()) {
                speak(original);
            } else {
                status("Backend вернул пустой текст");
            }
        } catch (Throwable e) {
            status("Ошибка ответа backend: " + shortError(e));
        }
    }

    private void translateLocalAndSpeak(String text) {
        Translator translator = localTranslator;
        if (translator == null) {
            speak(text);
            return;
        }
        translator.translate(text)
                .addOnSuccessListener(this::speak)
                .addOnFailureListener(error -> status("ML Kit перевод не удался: " + shortError(error)));
    }

    private void initLocalTranslatorIfNeeded() {
        if (!settings.useLocalTextTranslateFallback()) return;
        String source = mlLanguage(settings.sourceLang(), TranslateLanguage.ENGLISH);
        String target = mlLanguage(settings.targetLang(), TranslateLanguage.RUSSIAN);
        if (source.equals(target)) return;
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build();
        localTranslator = Translation.getClient(options);
        Task<Void> task = localTranslator.downloadModelIfNeeded();
        task.addOnSuccessListener(unused -> status("ML Kit модель текста готова"))
                .addOnFailureListener(error -> status("ML Kit модель не скачалась: " + shortError(error)));
    }

    private void initTts() {
        tts = new TextToSpeech(context, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) applyTtsSettings();
        });
    }

    private void applyTtsSettings() {
        if (tts == null) return;
        try { tts.setLanguage(Locale.forLanguageTag(settings.targetTtsLocale())); } catch (Throwable ignored) {}
        try { tts.setSpeechRate(settings.ttsRate()); } catch (Throwable ignored) {}
        try { tts.setPitch(settings.ttsPitch()); } catch (Throwable ignored) {}
    }

    private void speak(String text) {
        String fixed = normalize(text);
        if (fixed.length() < 2 || fixed.equalsIgnoreCase(lastSpoken)) return;
        lastSpoken = fixed;
        translatedText(fixed);
        if (tts == null || !ttsReady) {
            status("TTS не готов: " + fixed);
            return;
        }
        applyTtsSettings();
        Bundle params = new Bundle();
        int queueMode = tts.isSpeaking() ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
        tts.speak(fixed, queueMode, params, "ltm-internal-" + UUID.randomUUID());
        status("Озвучиваю перевод");
    }

    private byte[] makeWav(byte[] pcm, int sampleRate) throws IOException {
        int dataLen = pcm.length;
        int totalLen = 36 + dataLen;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        writeAscii(out, "RIFF");
        writeIntLE(out, totalLen);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, 1);
        writeShortLE(out, 1);
        writeIntLE(out, sampleRate);
        writeIntLE(out, sampleRate * 2);
        writeShortLE(out, 2);
        writeShortLE(out, 16);
        writeAscii(out, "data");
        writeIntLE(out, dataLen);
        out.write(pcm);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) throws IOException { out.write(value.getBytes()); }
    private static void writeIntLE(ByteArrayOutputStream out, int value) throws IOException {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }
    private static void writeShortLE(ByteArrayOutputStream out, int value) throws IOException {
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array());
    }

    private double rms16(byte[] pcm) {
        if (pcm.length < 2) return 0;
        long sum = 0;
        int count = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int lo = pcm[i] & 0xff;
            int hi = pcm[i + 1];
            int sample = (hi << 8) | lo;
            sum += (long) sample * sample;
            count++;
        }
        return count == 0 ? 0 : Math.sqrt(sum / (double) count);
    }

    private String first(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private String mlLanguage(String language, String fallback) {
        String fixed = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (fixed.startsWith("ru")) return TranslateLanguage.RUSSIAN;
        if (fixed.startsWith("en")) return TranslateLanguage.ENGLISH;
        if (fixed.startsWith("de")) return TranslateLanguage.GERMAN;
        if (fixed.startsWith("fr")) return TranslateLanguage.FRENCH;
        if (fixed.startsWith("es")) return TranslateLanguage.SPANISH;
        if (fixed.startsWith("it")) return TranslateLanguage.ITALIAN;
        if (fixed.startsWith("pt")) return TranslateLanguage.PORTUGUESE;
        if (fixed.startsWith("pl")) return TranslateLanguage.POLISH;
        if (fixed.startsWith("uk")) return TranslateLanguage.UKRAINIAN;
        if (fixed.startsWith("tr")) return TranslateLanguage.TURKISH;
        return fallback;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private void status(String text) { main.post(() -> callback.onStatus(text)); }
    private void originalText(String text) { main.post(() -> callback.onOriginalText(text)); }
    private void translatedText(String text) { main.post(() -> callback.onTranslatedText(text)); }

    private String crop(String value) {
        String fixed = value == null ? "" : value.replace('\n', ' ').trim();
        return fixed.length() > 220 ? fixed.substring(0, 220) : fixed;
    }

    private String shortError(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) return cause.getClass().getSimpleName();
        return crop(message);
    }
}
