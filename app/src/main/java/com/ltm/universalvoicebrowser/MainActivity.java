package com.ltm.universalvoicebrowser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.List;

public class MainActivity extends Activity implements InternalAudioTranslator.StatusCallback {
    private static final int REQ_MEDIA_PROJECTION = 9401;
    private static final int REQ_NOTIFICATIONS = 9402;

    private final int bg = Color.rgb(16, 19, 26);
    private final int panel = Color.rgb(23, 27, 36);
    private final int buttonBg = Color.rgb(31, 36, 48);
    private final int border = Color.rgb(52, 60, 78);
    private final int accent = Color.rgb(44, 201, 255);
    private final int danger = Color.rgb(255, 100, 110);

    private SettingsStore settings;
    private GeckoRuntime runtime;
    private GeckoSession session;
    private GeckoView geckoView;
    private ExtensionInstaller extensionInstaller;
    private InternalAudioTranslator translator;

    private EditText addressBar;
    private TextView statusBar;
    private TextView lastLine;
    private Button voiceButton;
    private LinearLayout topPanel;
    private String currentUrl = "";
    private boolean pendingCaptureStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        settings = new SettingsStore(this);
        translator = new InternalAudioTranslator(this, settings, this);
        createBrowser();
        createUi();
        extensionInstaller = new ExtensionInstaller(this, runtime);
        extensionInstaller.ensureBuiltInShield();
        requestNotificationPermissionIfNeeded();

        String start = getIntent() != null && getIntent().getDataString() != null
                ? getIntent().getDataString()
                : settings.homeUrl();
        openUrl(start);
        if (settings.autoStartVoice()) requestInternalAudioCapture();
    }

    @Override
    protected void onDestroy() {
        try { if (translator != null) translator.shutdown(); } catch (Throwable ignored) {}
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(true);
        if (Build.VERSION.SDK_INT >= 21) {
            window.setStatusBarColor(bg);
            window.setNavigationBarColor(bg);
        }
        if (Build.VERSION.SDK_INT >= 23) window.getDecorView().setSystemUiVisibility(0);
    }

    private void createBrowser() {
        GeckoRuntimeSettings runtimeSettings = new GeckoRuntimeSettings.Builder()
                .remoteDebuggingEnabled(true)
                .consoleOutput(true)
                .build();
        runtime = GeckoRuntime.create(this, runtimeSettings);
        session = new GeckoSession();
        session.open(runtime);
        geckoView = new GeckoView(this);
        geckoView.setSession(session);
    }

    private void createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        applySystemInsets(root);

        topPanel = new LinearLayout(this);
        topPanel.setOrientation(LinearLayout.VERTICAL);
        topPanel.setPadding(dp(6), dp(7), dp(6), dp(5));
        topPanel.setBackgroundColor(panel);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.addView(chromeButton("‹", v -> safe(() -> session.goBack())));
        addressRow.addView(chromeButton("›", v -> safe(() -> session.goForward())));
        addressRow.addView(chromeButton("⟳", v -> safe(() -> session.reload())));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.rgb(155, 165, 180));
        addressBar.setTextSize(14);
        addressBar.setHint("Адрес сайта или поисковый запрос");
        addressBar.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setPadding(dp(12), 0, dp(12), 0);
        addressBar.setBackground(round(Color.rgb(35, 40, 52), dp(16), border));
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                openUrl(addressBar.getText().toString());
                return true;
            }
            return false;
        });
        addressRow.addView(addressBar, new LinearLayout.LayoutParams(0, dp(42), 1));
        addressRow.addView(chromeButton("Go", v -> openUrl(addressBar.getText().toString())));
        topPanel.addView(addressRow, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView toolsScroll = new HorizontalScrollView(this);
        toolsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(0, dp(6), 0, 0);
        tools.addView(chromeButton("Домой", v -> openUrl(settings.homeUrl())));
        voiceButton = chromeButton("🎧 Внутр. перевод", v -> toggleInternalVoice());
        tools.addView(voiceButton);
        tools.addView(chromeButton("■ Стоп", v -> stopInternalVoice()));
        tools.addView(chromeButton("Оригинал −", v -> changePageMediaVolume(-0.12f)));
        tools.addView(chromeButton("Оригинал +", v -> changePageMediaVolume(0.12f)));
        tools.addView(chromeButton("Закладка", v -> addBookmark())) ;
        tools.addView(chromeButton("Закладки", v -> showUrlList("Закладки", settings.bookmarks(), false)));
        tools.addView(chromeButton("История", v -> showUrlList("История", settings.history(), true)));
        tools.addView(chromeButton("Расширения", v -> showExtensionsDialog())) ;
        tools.addView(chromeButton("⚙", v -> showSettingsDialog()));
        toolsScroll.addView(tools, new HorizontalScrollView.LayoutParams(-2, -2));
        topPanel.addView(toolsScroll, new LinearLayout.LayoutParams(-1, -2));

        statusBar = new TextView(this);
        statusBar.setTextColor(Color.rgb(205, 218, 230));
        statusBar.setTextSize(12);
        statusBar.setSingleLine(false);
        statusBar.setText("Готово. Откройте любой сайт, включите видео и нажмите 🎧 Внутр. перевод. Захват идёт через Android MediaProjection/AudioPlaybackCapture.");
        statusBar.setPadding(dp(4), dp(5), dp(4), 0);
        topPanel.addView(statusBar, new LinearLayout.LayoutParams(-1, -2));

        root.addView(topPanel, new LinearLayout.LayoutParams(-1, -2));
        root.addView(geckoView, new LinearLayout.LayoutParams(-1, 0, 1));

        lastLine = new TextView(this);
        lastLine.setTextColor(Color.rgb(220, 230, 240));
        lastLine.setTextSize(12);
        lastLine.setPadding(dp(8), dp(6), dp(8), dp(6));
        lastLine.setBackgroundColor(Color.rgb(12, 15, 20));
        lastLine.setText("Перевод не запущен");
        root.addView(lastLine, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void applySystemInsets(View root) {
        if (Build.VERSION.SDK_INT >= 20) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                view.setPadding(0, Math.max(0, top), 0, Math.max(0, bottom));
                return insets;
            });
        } else {
            root.setPadding(0, getStatusBarHeight(), 0, 0);
        }
    }

    private Button chromeButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(round(buttonBg, dp(12), border));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(lp);
        return button;
    }

    private void openUrl(String input) {
        String url = normalizeUrl(input);
        currentUrl = url;
        addressBar.setText(url);
        settings.addHistory(url);
        safe(() -> session.loadUri(url));
    }

    private String normalizeUrl(String value) {
        String fixed = value == null ? "" : value.trim();
        if (fixed.isEmpty()) return settings.homeUrl();
        if (fixed.startsWith("http://") || fixed.startsWith("https://") || fixed.startsWith("about:") || fixed.startsWith("file:")) return fixed;
        if (fixed.contains(".") && !fixed.contains(" ")) return "https://" + fixed;
        String search = settings.searchUrl();
        if (!search.contains("%s")) search = "https://www.google.com/search?q=%s";
        return search.replace("%s", Uri.encode(fixed));
    }

    private void toggleInternalVoice() {
        if (translator.isRunning()) stopInternalVoice();
        else requestInternalAudioCapture();
    }

    private void requestInternalAudioCapture() {
        String endpoint = settings.backendUrl().trim();
        if (endpoint.isEmpty()) {
            showSettingsDialog();
            toast("Сначала укажите backend endpoint");
            return;
        }
        if (settings.autoLowerOriginal()) changePageMediaVolumeTo(settings.originalVolume());
        pendingCaptureStart = true;
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                toast("MediaProjection недоступен на этом устройстве");
                return;
            }
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        } catch (Throwable e) {
            toast("Не удалось запросить захват аудио: " + shortError(e));
        }
    }

    private void stopInternalVoice() {
        pendingCaptureStart = false;
        translator.stop();
        if (voiceButton != null) {
            voiceButton.setText("🎧 Внутр. перевод");
            voiceButton.setBackground(round(buttonBg, dp(12), border));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (!pendingCaptureStart || resultCode != RESULT_OK || data == null) {
                toast("Разрешение на внутренний звук не выдано");
                return;
            }
            CaptureForegroundService.start(this);
            translator.start(resultCode, data);
            if (voiceButton != null) {
                voiceButton.setText("● Перевод идёт");
                voiceButton.setBackground(round(Color.rgb(20, 90, 70), dp(12), accent));
            }
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void changePageMediaVolume(float delta) {
        String js = "(()=>{let changed=0;for(const m of document.querySelectorAll('video,audio')){" +
                "m.volume=Math.max(0,Math.min(1,(m.volume||1)+(" + delta + ")));changed++;}" +
                "return changed;})()";
        runPageJavaScript(js);
    }

    private void changePageMediaVolumeTo(float volume) {
        float fixed = Math.max(0f, Math.min(1f, volume));
        String js = "(()=>{let changed=0;for(const m of document.querySelectorAll('video,audio')){" +
                "m.volume=" + fixed + ";changed++;}return changed;})()";
        runPageJavaScript(js);
    }

    private void runPageJavaScript(String js) {
        if (session == null || js == null || js.trim().isEmpty()) return;
        safe(() -> session.loadUri("javascript:" + Uri.encode(js)));
    }

    private void addBookmark() {
        String url = addressBar.getText().toString().trim();
        if (url.isEmpty()) url = currentUrl;
        settings.addBookmark(url);
        toast("Закладка добавлена");
    }

    private void showUrlList(String title, List<String> values, boolean canClear) {
        if (values == null || values.isEmpty()) {
            toast(title + " пусто");
            return;
        }
        String[] items = values.toArray(new String[0]);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (dialog, which) -> openUrl(items[which]))
                .setNegativeButton("Закрыть", null);
        if (canClear) builder.setNeutralButton("Очистить", (d, w) -> { settings.clearHistory(); toast("История очищена"); });
        builder.show();
    }

    private void showExtensionsDialog() {
        final EditText xpi = new EditText(this);
        xpi.setHint("https://.../extension.xpi");
        xpi.setSingleLine(true);
        xpi.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        xpi.setTextColor(Color.WHITE);
        xpi.setHintTextColor(Color.GRAY);
        xpi.setPadding(dp(12), dp(8), dp(12), dp(8));
        xpi.setBackground(round(Color.rgb(34, 39, 52), dp(12), border));

        LinearLayout layout = dialogLayout();
        layout.addView(dialogText("Расширения ставятся через Mozilla Add-ons или по прямой подписанной .xpi ссылке. Встроенный LTM Shield включается автоматически."));
        layout.addView(dialogButton("Открыть Mozilla Add-ons", v -> openUrl("https://addons.mozilla.org/android/")));
        layout.addView(dialogButton("Открыть uBlock Origin", v -> openUrl("https://addons.mozilla.org/android/addon/ublock-origin/")));
        layout.addView(dialogText("Прямая .xpi ссылка:"));
        layout.addView(xpi, new LinearLayout.LayoutParams(-1, dp(48)));
        layout.addView(dialogButton("Установить .xpi", v -> extensionInstaller.installXpi(xpi.getText().toString())));
        layout.addView(dialogButton("Переустановить LTM Shield", v -> extensionInstaller.ensureBuiltInShield()));

        showScrollDialog("Расширения", layout);
    }

    private void showSettingsDialog() {
        LinearLayout layout = dialogLayout();
        EditText home = edit(settings.homeUrl(), "Домашняя страница");
        EditText search = edit(settings.searchUrl(), "URL поиска с %s");
        EditText backend = edit(settings.backendUrl(), "http://IP:8787/translate-audio");
        EditText backendKey = edit(settings.backendApiKey(), "Bearer token для backend, если нужен");
        EditText source = edit(settings.sourceLang(), "Язык оригинала: en / auto / de / fr");
        EditText target = edit(settings.targetLang(), "Язык перевода: ru");
        EditText ttsLocale = edit(settings.targetTtsLocale(), "Голос TTS: ru-RU");
        EditText sampleRate = numberEdit(String.valueOf(settings.captureSampleRate()), "16000 или 44100");
        EditText chunk = numberEdit(String.valueOf(settings.chunkMs()), "Размер чанка, мс");
        EditText threshold = numberEdit(String.valueOf(settings.silenceThreshold()), "Порог тишины");
        EditText maxPending = numberEdit(String.valueOf(settings.maxPendingRequests()), "Параллельные запросы 1-5");
        CheckBox lowerOriginal = check("Автоматически приглушать оригинал", settings.autoLowerOriginal());
        CheckBox localFallback = check("Если backend вернул только распознанный текст — переводить ML Kit", settings.useLocalTextTranslateFallback());
        CheckBox autoStart = check("Автозапуск перевода при старте", settings.autoStartVoice());
        SeekBar originalVolume = seek((int) (settings.originalVolume() * 100));
        SeekBar ttsRate = seek((int) (settings.ttsRate() * 100));
        SeekBar ttsPitch = seek((int) (settings.ttsPitch() * 100));

        layout.addView(dialogText("Основные настройки браузера"));
        layout.addView(label("Домашняя страница")); layout.addView(home);
        layout.addView(label("Поиск")); layout.addView(search);
        layout.addView(dialogText("Внутренний перевод: Android захватывает системный звук браузера, отправляет WAV-чанки на backend, ответ озвучивается Android TTS."));
        layout.addView(label("Backend endpoint")); layout.addView(backend);
        layout.addView(label("Backend token, если используется")); layout.addView(backendKey);
        layout.addView(label("Язык оригинала")); layout.addView(source);
        layout.addView(label("Язык перевода")); layout.addView(target);
        layout.addView(label("TTS locale")); layout.addView(ttsLocale);
        layout.addView(label("Capture sample rate")); layout.addView(sampleRate);
        layout.addView(label("Chunk ms")); layout.addView(chunk);
        layout.addView(label("Silence threshold")); layout.addView(threshold);
        layout.addView(label("Max pending backend requests")); layout.addView(maxPending);
        layout.addView(lowerOriginal);
        layout.addView(label("Громкость оригинала при запуске, %")); layout.addView(originalVolume);
        layout.addView(label("Скорость голоса, %")); layout.addView(ttsRate);
        layout.addView(label("Тон голоса, %")); layout.addView(ttsPitch);
        layout.addView(localFallback);
        layout.addView(autoStart);

        new AlertDialog.Builder(this)
                .setTitle("Настройки LTM UniversalVoice")
                .setView(scroll(layout))
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    settings.setHomeUrl(home.getText().toString());
                    settings.setSearchUrl(search.getText().toString());
                    settings.setBackendUrl(backend.getText().toString());
                    settings.setBackendApiKey(backendKey.getText().toString());
                    settings.setSourceLang(source.getText().toString());
                    settings.setTargetLang(target.getText().toString());
                    settings.setTargetTtsLocale(ttsLocale.getText().toString());
                    settings.setCaptureSampleRate(parseInt(sampleRate.getText().toString(), 16000));
                    settings.setChunkMs(parseInt(chunk.getText().toString(), 3600));
                    settings.setSilenceThreshold(parseInt(threshold.getText().toString(), 280));
                    settings.setMaxPendingRequests(parseInt(maxPending.getText().toString(), 2));
                    settings.setAutoLowerOriginal(lowerOriginal.isChecked());
                    settings.setOriginalVolume(originalVolume.getProgress() / 100f);
                    settings.setTtsRate(ttsRate.getProgress() / 100f);
                    settings.setTtsPitch(ttsPitch.getProgress() / 100f);
                    settings.setUseLocalTextTranslateFallback(localFallback.isChecked());
                    settings.setAutoStartVoice(autoStart.isChecked());
                    toast("Настройки сохранены");
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private LinearLayout dialogLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(12), dp(16), dp(6));
        return layout;
    }

    private TextView dialogText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(225, 232, 240));
        t.setTextSize(14);
        t.setPadding(0, dp(5), 0, dp(8));
        return t;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(120, 220, 255));
        t.setTextSize(12);
        t.setPadding(0, dp(8), 0, dp(3));
        return t;
    }

    private Button dialogButton(String text, View.OnClickListener listener) {
        Button b = chromeButton(text, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private EditText edit(String value, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setPadding(dp(10), 0, dp(10), 0);
        e.setBackground(round(Color.rgb(34, 39, 52), dp(10), border));
        return e;
    }

    private EditText numberEdit(String value, String hint) {
        EditText e = edit(value, hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(Color.WHITE);
        box.setChecked(checked);
        box.setPadding(0, dp(6), 0, dp(2));
        return box;
    }

    private SeekBar seek(int progress) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(180);
        bar.setProgress(Math.max(0, Math.min(180, progress)));
        return bar;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(child);
        return scroll;
    }

    private void showScrollDialog(String title, LinearLayout layout) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll(layout))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Throwable e) { return fallback; }
    }

    private void safe(Runnable runnable) {
        try { runnable.run(); } catch (Throwable e) { toast(shortError(e)); }
    }

    private GradientDrawable round(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : dp(24);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private String shortError(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) return cause.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    @Override public void onStatus(String text) { statusBar.setText(text); }

    @Override public void onOriginalText(String text) {
        lastLine.setText("Оригинал: " + text);
    }

    @Override public void onTranslatedText(String text) {
        lastLine.setText("Озвучено: " + text);
    }
}
