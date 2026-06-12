package com.ltm.universalvoicebrowser;

import android.app.Activity;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;

import java.lang.reflect.Method;

public final class ExtensionInstaller {
    private static final String SHIELD_URI = "resource://android/assets/extensions/ltm_shield/";
    private static final String SHIELD_ID = "ltm-shield@ltm.local";

    private final Activity activity;
    private final GeckoRuntime runtime;

    public ExtensionInstaller(Activity activity, GeckoRuntime runtime) {
        this.activity = activity;
        this.runtime = runtime;
    }

    public void ensureBuiltInShield() {
        try {
            Object controller = controller();
            Method method = controller.getClass().getMethod("ensureBuiltIn", String.class, String.class);
            method.invoke(controller, SHIELD_URI, SHIELD_ID);
            toast("LTM Shield включён");
        } catch (Throwable first) {
            try {
                Object controller = controller();
                Method method = controller.getClass().getMethod("installBuiltIn", String.class, String.class);
                method.invoke(controller, SHIELD_URI, SHIELD_ID);
                toast("LTM Shield установлен");
            } catch (Throwable second) {
                toast("Не удалось установить LTM Shield: " + shortError(second));
            }
        }
    }

    public void installXpi(String xpiUrl) {
        String fixed = xpiUrl == null ? "" : xpiUrl.trim();
        if (!fixed.startsWith("http://") && !fixed.startsWith("https://") && !fixed.startsWith("file://")) {
            toast("Нужна прямая ссылка на подписанный .xpi");
            return;
        }
        try {
            Object controller = controller();
            Method method = controller.getClass().getMethod("install", String.class);
            method.invoke(controller, fixed);
            toast("Установка расширения запущена");
        } catch (Throwable e) {
            toast("Не удалось поставить .xpi: " + shortError(e));
        }
    }

    private Object controller() throws Exception {
        Method method = runtime.getClass().getMethod("getWebExtensionController");
        return method.invoke(runtime);
    }

    private void toast(String text) {
        activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
    }

    private static String shortError(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) return cause.getClass().getSimpleName();
        return message.length() > 140 ? message.substring(0, 140) : message;
    }
}
