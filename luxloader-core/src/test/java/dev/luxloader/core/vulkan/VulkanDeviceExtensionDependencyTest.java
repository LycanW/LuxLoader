package dev.luxloader.core.vulkan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the class of defect that produced
 * {@code VUID-vkCreateDevice-ppEnabledExtensionNames-01387}: enabling a device extension whose
 * dependency is not enabled.
 *
 * <h2>Why this test exists</h2>
 * That violation shipped through a fully green 341-test suite, because nothing in the suite knew
 * what the Vulkan spec says about extension dependencies. It only surfaced when a diagnostic run
 * happened to create a device on a validation-layer-enabled instance. Removing one extension from
 * the list fixes one instance of the class; this test fixes the class.
 *
 * <h2>What it checks</h2>
 * The dependency graph is read from the authoritative {@code vk.xml} shipped with the SDK -- not
 * hardcoded here -- so it cannot drift. For every extension in
 * {@link VulkanDevice#WANTED_DEVICE_EXTENSIONS} the {@code depends} attribute is parsed and
 * evaluated:
 *
 * <ul>
 *   <li>{@code ','} (outside parentheses) means OR, {@code '+'} means AND, parentheses group;</li>
 *   <li>a {@code VK_VERSION_1_x} term counts as satisfied. These are core promotions: a device
 *       that passes this loader's probe reports a 1.x API version at or above them, so the
 *       alternative is always available. If the loader ever lowers its minimum API version below
 *       what a term names, this assumption needs revisiting -- it is the one place this test
 *       reasons rather than reads;</li>
 *   <li>any other name must itself be in the wanted list. An INSTANCE extension (such as
 *       {@code VK_KHR_surface}) can never be, because that list is passed to
 *       {@code VkDeviceCreateInfo::ppEnabledExtensionNames} while the probe instance enables no
 *       instance extensions at all -- which is exactly the bug this catches.</li>
 * </ul>
 *
 * <p>Skipped when no SDK is present, following the pattern used by the other device tests.
 */
class VulkanDeviceExtensionDependencyTest {

    private record Extension(String name, String type, String depends) {
    }

    @Test
    @DisplayName("Wanted Extensions Have Satisfied Dependencies")
    void wantedExtensionsHaveSatisfiedDependencies() throws IOException {
        Path vkxml = findVkXml();
        Assumptions.assumeTrue(vkxml != null,
                "Vulkan SDK vk.xml unavailable; skipping dependency evaluation");

        Map<String, Extension> all = parseExtensions(Files.readString(vkxml, StandardCharsets.UTF_8));
        assertFalse(all.isEmpty(), "Parsed from " + vkxml + ": zero extensions, indicating a parser failure");

        Set<String> wanted = new HashSet<>(VulkanDevice.WANTED_DEVICE_EXTENSIONS);
        List<String> problems = new ArrayList<>();

        for (String name : VulkanDevice.WANTED_DEVICE_EXTENSIONS) {
            Extension e = all.get(name);
            if (e == null) {
                problems.add(name + "：vk.xml 里没有这个扩展名");
                continue;
            }
            if (!"device".equals(e.type())) {
                problems.add(name + "：type=" + e.type()
                        + "，但这个列表会传给 VkDeviceCreateInfo，只能放设备扩展");
                continue;
            }
            // An absent or empty depends means "no dependency".
            if (e.depends() == null || e.depends().isBlank()) {
                continue;
            }
            List<String> note = new ArrayList<>();
            boolean ok = new Expr(e.depends(), 0).eval(wanted, all, note);
            if (!ok) {
                problems.add(name + "：depends=\"" + e.depends() + "\" 未满足"
                        + (note.isEmpty() ? "" : "（" + String.join("；", note) + "）"));
            }
        }

        assertTrue(problems.isEmpty(),
                "Unsatisfied extension dependencies would fail vkCreateDevice: "
                        + "VUID-vkCreateDevice-ppEnabledExtensionNames-01387：\n  - "
                        + String.join("\n  - ", problems));
    }

    // Dependency expressions: comma means OR, plus means AND, with parentheses for grouping.

    /** Minimal recursive-descent evaluator for vk.xml's {@code depends} grammar. */
    private static final class Expr {
        private final String s;
        private int i;

        Expr(String s, int i) {
            this.s = s;
            this.i = i;
        }

        /** expr := term (',' term)* -- satisfied if ANY alternative is. */
        boolean eval(Set<String> wanted, Map<String, Extension> all, List<String> note) {
            List<String> failures = new ArrayList<>();
            while (true) {
                List<String> missing = new ArrayList<>();
                boolean ok = term(wanted, all, missing);
                if (ok) {
                    return true;
                }
                failures.add(String.join(" AND ", missing));
                if (i >= s.length() || s.charAt(i) != ',') {
                    break;
                }
                i++;
            }
            note.add(String.join(" OR ", failures));
            return false;
        }

        /** term := factor ('+' factor)* -- satisfied only if ALL factors are. */
        private boolean term(Set<String> wanted, Map<String, Extension> all, List<String> missing) {
            boolean ok = true;
            while (true) {
                if (!factor(wanted, all, missing)) {
                    ok = false;
                }
                if (i >= s.length() || s.charAt(i) != '+') {
                    return ok;
                }
                i++;
            }
        }

        private boolean factor(Set<String> wanted, Map<String, Extension> all, List<String> missing) {
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                boolean ok = eval(wanted, all, new ArrayList<>());
                if (i < s.length() && s.charAt(i) == ')') {
                    i++;
                }
                return ok;
            }
            int start = i;
            while (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != '+' && s.charAt(i) != ')') {
                i++;
            }
            String name = s.substring(start, i).trim();
            if (name.isEmpty()) {
                return true;
            }
            if (name.matches("VK_VERSION_1_\\d+")) {
                // Core promotion -- see the class javadoc for why this is treated as satisfied.
                return true;
            }
            if (wanted.contains(name)) {
                return true;
            }
            Extension dep = all.get(name);
            missing.add(name + (dep == null
                    ? " (absent from vk.xml)"
                    : "（" + dep.type() + " extension not enabled)"));
            return false;
        }
    }

    // ------------------------------------------------------------------
    // vk.xml
    // ------------------------------------------------------------------

    private static final Pattern EXTENSION_TAG = Pattern.compile("<extension\\b[^>]*>");
    private static final Pattern ATTR = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");

    private static Map<String, Extension> parseExtensions(String xml) {
        Map<String, Extension> out = new HashMap<>();
        Matcher m = EXTENSION_TAG.matcher(xml);
        while (m.find()) {
            Map<String, String> attrs = new HashMap<>();
            Matcher a = ATTR.matcher(m.group());
            while (a.find()) {
                attrs.put(a.group(1), a.group(2));
            }
            String name = attrs.get("name");
            if (name != null) {
                out.put(name, new Extension(name, attrs.get("type"), attrs.get("depends")));
            }
        }
        return out;
    }

    /** {@code VULKAN_SDK} first, then the newest {@code C:\VulkanSDK\*\share\vulkan\registry\vk.xml}. */
    private static Path findVkXml() throws IOException {
        String env = System.getenv("VULKAN_SDK");
        if (env != null && !env.isBlank()) {
            Path p = Paths.get(env, "share", "vulkan", "registry", "vk.xml");
            if (Files.isReadable(p)) {
                return p;
            }
        }
        Path root = Paths.get("C:\\VulkanSDK");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            List<Path> candidates = new ArrayList<>();
            for (Path d : dirs) {
                Path p = d.resolve("share").resolve("vulkan").resolve("registry").resolve("vk.xml");
                if (Files.isReadable(p)) {
                    candidates.add(p);
                }
            }
            // Newest version directory, by path name.
            candidates.sort(Comparator.comparing(Path::toString));
            return candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
        }
    }
}
