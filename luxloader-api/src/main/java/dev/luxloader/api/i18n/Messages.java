package dev.luxloader.api.i18n;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/** Shared UI and diagnostic translations. Source messages are written in English. */
public final class Messages {
    private static final Properties CHINESE = load("zh");
    private static volatile String language = normalize(System.getProperty("luxloader.language", Locale.getDefault().getLanguage()));

    private Messages() { }

    /** Hosts supply their selected language; unsupported languages fall back to English. */
    public static void setLanguage(String code) { language = normalize(code); }

    public static String language() { return language; }

    /** Translate at the point of display or logging so language changes apply immediately. */
    public static String tr(String english) {
        return tr(english, english);
    }

    /** Use a context key when identical English fragments have different translations. */
    public static String tr(String key, String english) {
        if (key == null || !language.equals("zh")) return english;
        return CHINESE.getProperty(key, english);
    }

    private static String normalize(String code) {
        if (code == null) return "en";
        String normalized = code.toLowerCase(Locale.ROOT);
        return normalized.equals("zh") || normalized.startsWith("zh_") || normalized.startsWith("zh-") ? "zh" : "en";
    }

    private static Properties load(String code) {
        var result = new Properties();
        try (var input = Messages.class.getResourceAsStream("messages_" + code + ".properties")) {
            if (input != null) result.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Source messages remain usable if a resource is unavailable.
        }
        return result;
    }
}
