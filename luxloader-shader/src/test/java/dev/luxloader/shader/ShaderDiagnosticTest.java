package dev.luxloader.shader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests diagnostic parsing using captured compiler output. Support both legacy file(line): error N:
 * message and newer error[EN]: message diagnostics with location arrows and source excerpts.
 */
class ShaderDiagnosticTest {

    /** 2026.x diagnostic with location, source excerpt and caret annotation. */
    private static final String MODERN_FORMAT = """
            error[E30015]: undefined identifier
             --> bad.comp:8:31
              |
            8 | imageStore(dstImg, p, c * undefinedSymbol);
              |                           ^^^^^^^^^^^^^^^ undefined identifier 'undefinedSymbol'.
            --'
            warning[E41012]: profile implicitly upgraded
             --> bad.comp:5:6
              |
            5 | void main() {
              |      ^^^^ entry point 'main' uses additional capabilities
            --'
            """;

    /** 2026.x diagnostics without locations, one per line. */
    private static final String ENVIRONMENT_FORMAT = """
            error[E00100]: failed to load downstream compiler 'spirv-opt'
            note[E99996]: failed to load dynamic library 'slang-glslang'
            """;

    @Test
    @DisplayName("Parses Modern Format")
    void parsesModernFormat() {
        List<ShaderDiagnostic> diagnostics = ShaderDiagnostic.parseAll(MODERN_FORMAT, null);

        assertEquals(2, diagnostics.size(), "Expected two diagnostics: " + diagnostics);

        ShaderDiagnostic error = diagnostics.get(0);
        assertEquals(ShaderDiagnostic.Severity.ERROR, error.severity());
        assertEquals("E30015", error.code());
        assertEquals("undefined identifier", error.message());
        assertEquals("bad.comp", error.file());
        assertEquals(8, error.line());
        assertEquals(31, error.column());
        assertEquals("undefined identifier 'undefinedSymbol'.", error.detail(),
                "Preserve the explanatory caret annotation");
        assertTrue(error.isError());
        assertTrue(error.hasLocation());

        // Preserve the source excerpt for diagnostic UI display.
        assertFalse(error.context().isEmpty(), "Preserve compiler source excerpts");
        assertTrue(error.context().stream().anyMatch(c -> c.lineNumber() == 8 && c.primary()),
                "Line eight must be marked as the error location: " + error.context());

        ShaderDiagnostic warning = diagnostics.get(1);
        assertEquals(ShaderDiagnostic.Severity.WARNING, warning.severity());
        assertEquals("E41012", warning.code());
        assertEquals(5, warning.line());
        assertFalse(warning.isError(), "Warnings must not be classified as errors");
    }

    @Test
    @DisplayName("Parses Environment Format")
    void parsesEnvironmentFormat() {
        List<ShaderDiagnostic> diagnostics = ShaderDiagnostic.parseAll(ENVIRONMENT_FORMAT, null);

        assertEquals(2, diagnostics.size());
        assertEquals(ShaderDiagnostic.Severity.ERROR, diagnostics.get(0).severity());
        assertEquals("E00100", diagnostics.get(0).code());
        assertTrue(diagnostics.get(0).message().contains("spirv-opt"));
        assertFalse(diagnostics.get(0).hasLocation(), "Environment failures must not invent source locations");

        assertEquals(ShaderDiagnostic.Severity.NOTE, diagnostics.get(1).severity(),
                "Notes must not increase the error count");
        assertEquals("E99996", diagnostics.get(1).code());
    }

    @Test
    @DisplayName("Strips Ansi Escapes")
    void stripsAnsiEscapes() {
        String colored = "\u001B[1m\u001B[31merror\u001B[0m\u001B[1m[E00001]\u001B[0m: cannot open file 'x.slang'";
        List<ShaderDiagnostic> diagnostics = ShaderDiagnostic.parseAll(colored, null);

        assertEquals(1, diagnostics.size());
        assertEquals(ShaderDiagnostic.Severity.ERROR, diagnostics.get(0).severity());
        assertEquals("E00001", diagnostics.get(0).code());
        assertEquals("cannot open file 'x.slang'", diagnostics.get(0).message());
        assertFalse(diagnostics.get(0).message().contains("\u001B"), "Strip terminal escape sequences");
    }

    @Test
    @DisplayName("Handles Windows Drive Letters")
    void handlesWindowsDriveLetters() {
        String output = """
                error[E30015]: undefined identifier
                 --> C:\\projects\\mymod\\shaders\\bloom.slang:42:7
                  |
                42 |     return thing;
                  |            ^^^^^ undefined identifier 'thing'.
                """;
        List<ShaderDiagnostic> diagnostics = ShaderDiagnostic.parseAll(output, null);

        assertEquals(1, diagnostics.size());
        ShaderDiagnostic d = diagnostics.get(0);
        assertEquals("C:\\projects\\mymod\\shaders\\bloom.slang", d.file());
        assertEquals(42, d.line());
        assertEquals(7, d.column());
    }

    @Test
    @DisplayName("Rewrites File Names")
    void rewritesFileNames() {
        List<ShaderDiagnostic> diagnostics = ShaderDiagnostic.parseAll(MODERN_FORMAT,
                file -> file.equals("bad.comp") ? "我的超分.slang" : file);

        assertEquals("我的超分.slang", diagnostics.get(0).file());
        assertEquals("我的超分.slang:8:31", diagnostics.get(0).location());
    }

    @Test
    @DisplayName("Keeps Unparsable Output")
    void keepsUnparsableOutput() {
        List<ShaderDiagnostic> diagnostics = ShaderDiagnostic.parseAll(
                "something went horribly wrong\nexit status 3", null);

        assertEquals(1, diagnostics.size(),
                "Preserve an unparsed diagnostic instead of hiding the failure reason");
        assertEquals(ShaderDiagnostic.Severity.INFO, diagnostics.get(0).severity());
        assertTrue(diagnostics.get(0).message().contains("horribly wrong"));
    }

    @Test
    @DisplayName("Handles Empty Input")
    void handlesEmptyInput() {
        assertTrue(ShaderDiagnostic.parseAll(null, null).isEmpty());
        assertTrue(ShaderDiagnostic.parseAll("", null).isEmpty());
        assertTrue(ShaderDiagnostic.parseAll("   \n  \n", null).isEmpty());
    }

    @Test
    @DisplayName("Provides Chinese Hints")
    void providesChineseHints() {
        assertTrue(hintFor("E00100", "failed to load downstream compiler 'spirv-opt'")
                        .contains("slang-glslang"),
                "Missing-component guidance must name the required file");
        assertTrue(hintFor("E30015", "undefined identifier").contains("include"));
        assertTrue(hintFor("E00001", "cannot open file 'x.slang'").contains("路径"));
        assertTrue(hintFor("E15900", "preprocessor error").contains("宏"));
        assertTrue(hintFor("E41012", "profile implicitly upgraded").contains("无害"),
                "Clarify when a warning does not prevent compilation");
        assertTrue(hintFor("", "syntax error, unexpected token").contains("语法"));
    }

    private static String hintFor(String code, String message) {
        return new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, code, message,
                "", 0, 0, "", List.of()).hint();
    }

    @Test
    @DisplayName("Render Sorts By Severity Then Location")
    void renderSortsBySeverityThenLocation() {
        List<ShaderDiagnostic> diagnostics = List.of(
                new ShaderDiagnostic(ShaderDiagnostic.Severity.WARNING, "E1", "警告", "b.slang", 1, 1, "", List.of()),
                new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, "E2", "后面的错", "a.slang", 90, 1, "", List.of()),
                new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, "E3", "前面的错", "a.slang", 10, 1, "", List.of()));

        String rendered = ShaderDiagnostic.render(diagnostics);
        int firstError = rendered.indexOf("前面的错");
        int secondError = rendered.indexOf("后面的错");
        int warning = rendered.indexOf("警告");

        assertTrue(firstError >= 0 && secondError > firstError,
                "Sort errors of equal severity by ascending line number");
        assertTrue(warning > secondError, "Warnings must follow errors");
    }

    @Test
    @DisplayName("Summarizes Counts")
    void summarizesCounts() {
        List<ShaderDiagnostic> diagnostics = List.of(
                new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, "", "a", "", 0, 0, "", List.of()),
                new ShaderDiagnostic(ShaderDiagnostic.Severity.ERROR, "", "b", "", 0, 0, "", List.of()),
                new ShaderDiagnostic(ShaderDiagnostic.Severity.WARNING, "", "c", "", 0, 0, "", List.of()));

        String summary = ShaderDiagnostic.summarize(diagnostics);
        assertTrue(summary.contains("2 条错误"), summary);
        assertTrue(summary.contains("1 条警告"), summary);
        assertEquals("无诊断", ShaderDiagnostic.summarize(List.of()));
    }

    @Test
    @DisplayName("Renders Readable Block")
    void rendersReadableBlock() {
        ShaderDiagnostic diagnostic = ShaderDiagnostic.parseAll(MODERN_FORMAT, null).get(0);
        String text = diagnostic.render();

        assertTrue(text.contains("错误[E30015]"), text);
        assertTrue(text.contains("bad.comp:8:31"), text);
        assertTrue(text.contains("undefinedSymbol"), text);
        assertTrue(text.contains("建议:"), text);
    }
}
