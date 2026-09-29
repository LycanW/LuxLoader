package dev.luxloader.core.runtime;

import dev.luxloader.api.i18n.Messages;
import dev.luxloader.core.diag.DiagnosticsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LocalizationIntegrationTest {
    @TempDir Path reports;

    @Test void existingSchemaAndReportSectionsFollowLanguageChanges() {
        String previous = Messages.language();
        try {
            Messages.setLanguage("zh_cn");
            var schema = LoaderConfig.schema();
            var diagnostics = new DiagnosticsImpl(reports, "test", false);
            diagnostics.section("Registered pipelines", () -> "example:pipeline");
            assertTrue(containsHan(diagnostics.exportReport()));
            Messages.setLanguage("en_us");
            String report = diagnostics.exportReport();
            assertTrue(report.contains("LuxLoader Diagnostic Report"));
            assertTrue(report.contains("--- Registered pipelines ---"));
            assertFalse(containsHan(report));
            for (var option : schema.options()) {
                assertFalse(containsHan(Messages.tr(option.displayName())));
                assertFalse(containsHan(Messages.tr(option.description())));
            }
            Messages.setLanguage("zh_cn");
            for (var option : schema.options()) {
                assertTrue(containsHan(Messages.tr(option.displayName())), option.path());
                assertTrue(containsHan(Messages.tr(option.description())), option.path());
            }
        } finally {
            Messages.setLanguage(previous);
        }
    }

    private static boolean containsHan(String text) {
        return text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
    }
}
