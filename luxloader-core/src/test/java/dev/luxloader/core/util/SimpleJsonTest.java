package dev.luxloader.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests JSON type mapping, escaping, nesting, atomic writes and recovery from corrupt configuration files. */
class SimpleJsonTest {

    @Test
    @DisplayName("Parses Primitives")
    void parsesPrimitives() {
        Object parsed = SimpleJson.parse("""
                {
                  "enabled": true,
                  "count": 42,
                  "ratio": 0.5,
                  "name": "luxloader",
                  "nothing": null
                }
                """);
        assertInstanceOf(Map.class, parsed);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertEquals(Boolean.TRUE, map.get("enabled"));
        assertEquals(42L, map.get("count"));
        assertEquals(0.5, map.get("ratio"));
        assertEquals("luxloader", map.get("name"));
        assertNull(map.get("nothing"));
        assertEquals(List.of("enabled", "count", "ratio", "name", "nothing"),
                List.copyOf(map.keySet()));
    }

    @Test
    @DisplayName("Parses Nested")
    void parsesNested() {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) SimpleJson.parse("""
                {
                  "pipeline": {
                    "id": "example:my-pipeline",
                    "attachments": ["UPSCALE", "BEFORE_PRESENT"],
                    "quality": { "mode": "quality", "sharpness": 0.35 }
                  }
                }
                """);
        @SuppressWarnings("unchecked")
        Map<String, Object> pipeline = (Map<String, Object>) map.get("pipeline");
        assertEquals("example:my-pipeline", pipeline.get("id"));
        assertEquals(List.of("UPSCALE", "BEFORE_PRESENT"), pipeline.get("attachments"));
        @SuppressWarnings("unchecked")
        Map<String, Object> quality = (Map<String, Object>) pipeline.get("quality");
        assertEquals("quality", quality.get("mode"));
        assertEquals(0.35, quality.get("sharpness"));
    }

    @Test
    @DisplayName("Escapes Round Trip")
    void escapesRoundTrip() {
        String tricky = "带中文 \"引号\" 和\\反斜杠\n换行\t制表";
        String json = SimpleJson.write(Map.of("text", tricky));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) SimpleJson.parse(json);
        assertEquals(tricky, back.get("text"));
    }

    @Test
    @DisplayName("Write Then Parse")
    void writeThenParse() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("a", 1L);
        original.put("b", 2.5);
        original.put("c", List.of(1L, 2L, 3L));
        original.put("d", Map.of("nested", true));

        @SuppressWarnings("unchecked")
        Map<String, Object> fromCompact = (Map<String, Object>) SimpleJson.parse(SimpleJson.write(original));
        @SuppressWarnings("unchecked")
        Map<String, Object> fromPretty = (Map<String, Object>) SimpleJson.parse(SimpleJson.writePretty(original, 2));
        assertEquals(original.keySet(), fromCompact.keySet());
        assertEquals(original.keySet(), fromPretty.keySet());
        assertEquals(List.of(1L, 2L, 3L), fromPretty.get("c"));
        assertTrue(SimpleJson.writePretty(original, 2).contains("\n  \"a\": 1"));
    }

    @Test
    @DisplayName("Writes Integral Doubles Without Decimal")
    void writesIntegralDoublesWithoutDecimal() {
        String json = SimpleJson.write(Map.of("v", 2.0d));
        assertTrue(json.contains("\"v\":2"), "Actual output: " + json);
        assertFalse(json.contains("2.0"));
    }

    @Test
    @DisplayName("Rejects Malformed")
    void rejectsMalformed() {
        SimpleJson.JsonException e = assertThrows(SimpleJson.JsonException.class,
                () -> SimpleJson.parse("{ \"a\": }"));
        assertTrue(e.getMessage().contains("行"), "Error messages must include a location: " + e.getMessage());

        assertThrows(SimpleJson.JsonException.class, () -> SimpleJson.parse("{ \"a\": 1 } trailing"));
        assertThrows(SimpleJson.JsonException.class, () -> SimpleJson.parse("[1, 2"));
        assertThrows(SimpleJson.JsonException.class, () -> SimpleJson.parse("{ a: 1 }"));
    }

    @Test
    @DisplayName("Parse Object Requires Object")
    void parseObjectRequiresObject() {
        assertThrows(SimpleJson.JsonException.class, () -> SimpleJson.parseObject("[1,2,3]"));
        assertEquals(Map.of("k", 1L), SimpleJson.parseObject("{\"k\":1}"));
    }

    @Test
    @DisplayName("Atomic Write")
    void atomicWrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("luxloader.json");
        Map<String, Object> first = Map.of("_version", 1L, "enabled", true);
        SimpleJson.writeFileAtomic(file, first, 2);
        assertTrue(Files.exists(file));

        Map<String, Object> second = Map.of("_version", 1L, "enabled", false);
        SimpleJson.writeFileAtomic(file, second, 2);

        assertTrue(Files.exists(dir.resolve("luxloader.json.bak")), "Preserve the previous file as a backup");
        @SuppressWarnings("unchecked")
        Map<String, Object> read = (Map<String, Object>) SimpleJson.parse(Files.readString(file));
        assertEquals(Boolean.FALSE, read.get("enabled"));
        // No temporary file should remain.
        assertFalse(Files.exists(dir.resolve("luxloader.json.tmp")));
    }

    @Test
    @DisplayName("Read Missing File Is Empty")
    void readMissingFileIsEmpty(@TempDir Path dir) throws IOException {
        assertTrue(SimpleJson.readFile(dir.resolve("nope.json")).isEmpty());
    }

    @Test
    @DisplayName("Deep Merge")
    void deepMerge() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("keep", 1L);
        base.put("nested", new LinkedHashMap<>(Map.of("a", 1L, "b", 2L)));

        Map<String, Object> overlay = new LinkedHashMap<>();
        overlay.put("nested", new LinkedHashMap<>(Map.of("b", 99L, "c", 3L)));
        overlay.put("added", "x");

        @SuppressWarnings("unchecked")
        Map<String, Object> merged = SimpleJson.deepMerge(base, overlay);
        assertEquals(1L, merged.get("keep"));
        assertEquals("x", merged.get("added"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) merged.get("nested");
        assertEquals(1L, nested.get("a"), "Preserve nested keys not overwritten by the update");
        assertEquals(99L, nested.get("b"));
        assertEquals(3L, nested.get("c"));
    }
}
