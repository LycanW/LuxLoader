package dev.luxloader.api.i18n;

import org.junit.jupiter.api.Test;
import dev.luxloader.api.capability.CapabilityLevel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @Test void languageChangesApplyImmediatelyAndUnknownKeysStayUsable() throws Exception {
        String previous = Messages.language();
        try {
            var chinese = catalog("zh");
            String translated = chinese.getProperty("Apply");
            assertNotNull(translated);
            assertNotEquals("Apply", translated);
            for (String code : new String[] {"zh", "zh_cn", "zh-TW", "ZH_hk"}) {
                Messages.setLanguage(code);
                assertEquals(translated, Messages.tr("Apply"));
                assertEquals("plugin.unknown", Messages.tr("plugin.unknown"));
            }
            for (String code : new String[] {"en_us", "fr_fr", "zhunknown", "", null}) {
                Messages.setLanguage(code);
                assertEquals("Apply", Messages.tr("Apply"));
                assertNull(Messages.tr(null));
            }
        } finally {
            Messages.setLanguage(previous);
        }
    }

    @Test void catalogsHaveMatchingKeysAndFormattingArguments() throws Exception {
        var english = catalog("en");
        var chinese = catalog("zh");
        assertFalse(english.isEmpty());
        assertEquals(english.keySet(), chinese.keySet());
        var arguments = Pattern.compile("\\{\\}|%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]");
        for (String key : english.stringPropertyNames()) {
            assertFalse(english.getProperty(key).isEmpty(), key);
            assertEquals(arguments.matcher(english.getProperty(key)).results().map(m -> m.group()).toList(),
                    arguments.matcher(chinese.getProperty(key)).results().map(m -> m.group()).toList(), key);
        }
    }

    @Test void enumLabelsAreNotFrozenAtClassInitialization() {
        String previous = Messages.language();
        try {
            Messages.setLanguage("en_us");
            assertEquals("Native SDK", CapabilityLevel.NATIVE.displayName());
            Messages.setLanguage("zh_cn");
            assertNotEquals("Native SDK", CapabilityLevel.NATIVE.displayName());
            Messages.setLanguage("en_us");
            assertEquals("native(Native SDK)", CapabilityLevel.NATIVE.toString());
        } finally {
            Messages.setLanguage(previous);
        }
    }

    private static Properties catalog(String language) throws Exception {
        var result = new Properties();
        try (var input = Messages.class.getResourceAsStream("messages_" + language + ".properties")) {
            assertNotNull(input);
            result.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return result;
    }
}
