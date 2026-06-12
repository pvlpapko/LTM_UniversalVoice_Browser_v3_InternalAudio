package com.ltm.universalvoicebrowser;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class SettingsStore {
    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        prefs = context.getSharedPreferences("ltm_universal_voice_browser_v3", Context.MODE_PRIVATE);
    }

    public String homeUrl() { return prefs.getString("homeUrl", "https://www.google.com"); }
    public void setHomeUrl(String value) { putString("homeUrl", nonEmpty(value, "https://www.google.com")); }

    public String searchUrl() { return prefs.getString("searchUrl", "https://www.google.com/search?q=%s"); }
    public void setSearchUrl(String value) { putString("searchUrl", nonEmpty(value, "https://www.google.com/search?q=%s")); }

    public String backendUrl() { return prefs.getString("backendUrl", "http://10.0.2.2:8787/translate-audio"); }
    public void setBackendUrl(String value) { putString("backendUrl", nonEmpty(value, "http://10.0.2.2:8787/translate-audio")); }

    public String backendApiKey() { return prefs.getString("backendApiKey", ""); }
    public void setBackendApiKey(String value) { putString("backendApiKey", value == null ? "" : value.trim()); }

    public String sourceLang() { return prefs.getString("sourceLang", "en"); }
    public void setSourceLang(String value) { putString("sourceLang", nonEmpty(value, "en")); }

    public String targetLang() { return prefs.getString("targetLang", "ru"); }
    public void setTargetLang(String value) { putString("targetLang", nonEmpty(value, "ru")); }

    public String targetTtsLocale() { return prefs.getString("targetTtsLocale", "ru-RU"); }
    public void setTargetTtsLocale(String value) { putString("targetTtsLocale", nonEmpty(value, "ru-RU")); }

    public int captureSampleRate() { return prefs.getInt("captureSampleRate", 16000); }
    public void setCaptureSampleRate(int value) { prefs.edit().putInt("captureSampleRate", clampInt(value, 8000, 48000)).apply(); }

    public int chunkMs() { return prefs.getInt("chunkMs", 3600); }
    public void setChunkMs(int value) { prefs.edit().putInt("chunkMs", clampInt(value, 1200, 9000)).apply(); }

    public int silenceThreshold() { return prefs.getInt("silenceThreshold", 280); }
    public void setSilenceThreshold(int value) { prefs.edit().putInt("silenceThreshold", clampInt(value, 0, 3000)).apply(); }

    public int maxPendingRequests() { return prefs.getInt("maxPendingRequests", 2); }
    public void setMaxPendingRequests(int value) { prefs.edit().putInt("maxPendingRequests", clampInt(value, 1, 5)).apply(); }

    public float originalVolume() { return prefs.getFloat("originalVolume", 0.45f); }
    public void setOriginalVolume(float value) { prefs.edit().putFloat("originalVolume", clamp(value, 0f, 1f)).apply(); }

    public boolean autoLowerOriginal() { return prefs.getBoolean("autoLowerOriginal", true); }
    public void setAutoLowerOriginal(boolean value) { prefs.edit().putBoolean("autoLowerOriginal", value).apply(); }

    public boolean useLocalTextTranslateFallback() { return prefs.getBoolean("useLocalTextTranslateFallback", true); }
    public void setUseLocalTextTranslateFallback(boolean value) { prefs.edit().putBoolean("useLocalTextTranslateFallback", value).apply(); }

    public float ttsRate() { return prefs.getFloat("ttsRate", 1.05f); }
    public void setTtsRate(float value) { prefs.edit().putFloat("ttsRate", clamp(value, 0.45f, 1.8f)).apply(); }

    public float ttsPitch() { return prefs.getFloat("ttsPitch", 1.0f); }
    public void setTtsPitch(float value) { prefs.edit().putFloat("ttsPitch", clamp(value, 0.5f, 1.8f)).apply(); }

    public boolean autoStartVoice() { return prefs.getBoolean("autoStartVoice", false); }
    public void setAutoStartVoice(boolean value) { prefs.edit().putBoolean("autoStartVoice", value).apply(); }

    public void addHistory(String url) { addLineItem("history", url, 80); }
    public List<String> history() { return lines("history"); }

    public void addBookmark(String url) { addLineItem("bookmarks", url, 150); }
    public List<String> bookmarks() { return lines("bookmarks"); }
    public void clearHistory() { prefs.edit().remove("history").apply(); }

    private void addLineItem(String key, String value, int max) {
        String fixed = value == null ? "" : value.trim();
        if (fixed.isEmpty()) return;
        List<String> old = lines(key);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(fixed);
        set.addAll(old);
        List<String> limited = new ArrayList<>();
        int count = 0;
        for (String item : set) {
            if (count++ >= max) break;
            limited.add(item);
        }
        prefs.edit().putString(key, join(limited)).apply();
    }

    private List<String> lines(String key) {
        String raw = prefs.getString(key, "");
        List<String> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        String[] parts = raw.split("\\n");
        for (String part : parts) {
            String item = part.trim();
            if (!item.isEmpty()) list.add(item);
        }
        return list;
    }

    private String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append('\n');
            out.append(value);
        }
        return out.toString();
    }

    private void putString(String key, String value) { prefs.edit().putString(key, value).apply(); }

    private static String nonEmpty(String value, String fallback) {
        String fixed = value == null ? "" : value.trim();
        return fixed.isEmpty() ? fallback : fixed;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
