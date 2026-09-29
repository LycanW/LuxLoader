package dev.luxloader.api.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks {@link PromotedExtensions} against the Vulkan SDK registry. An extra entry can advertise
 * unavailable functionality; a missing entry can reject a supported promoted feature. Locate vk.xml
 * through VULKAN_SDK or the latest C:\VulkanSDK installation, and skip explicitly when no SDK is
 * installed.
 */
class PromotedExtensionsTest {

    /** Only promotions to a core version count; promotion to another extension does not. */
    private static final Pattern EXT =
            Pattern.compile("<extension\\s+name=\"([^\"]+)\"([^>]*?)/?>", Pattern.DOTALL);
    private static final Pattern PROMOTED =
            Pattern.compile("promotedto=\"(VK_VERSION_(\\d+)_(\\d+))\"");
    private static final Pattern TYPE = Pattern.compile("type=\"(device|instance)\"");

    private static GpuCapabilities caps(int apiMajor, int apiMinor, Set<String> deviceExts) {
        // apiVersion encoding: major << 22 | minor << 12 | patch.
        int api = (apiMajor << 22) | (apiMinor << 12);
        return new GpuCapabilities("test", GpuVendor.UNKNOWN, 0, 0, 0, "", api,
                deviceExts, Set.of(), 0, 0, 0f, 0L, 0);
    }

    @Test
    @DisplayName("Promoted Extension Counts As Available")
    void promotedExtensionCountsAsAvailable() {
        // The RT example requires this Vulkan 1.1 core feature even when a Vulkan 1.4 driver omits its extension name.
        GpuCapabilities v14 = caps(1, 4, Set.of());
        assertTrue(v14.supports("VK_KHR_get_memory_requirements2"),
                "Available on Vulkan 1.4 because it is core in 1.1");
        assertTrue(v14.supportsInstance("VK_KHR_get_physical_device_properties2"),
                "This is an instance extension; query the separate instance promotion table");
        assertTrue(v14.supports("VK_KHR_synchronization2"), "Core in Vulkan 1.3");
        assertTrue(v14.supports("VK_KHR_dynamic_rendering"), "Core in Vulkan 1.3");
    }

    @Test
    @DisplayName("Promotion Respects Device Version")
    void promotionRespectsDeviceVersion() {
        GpuCapabilities v10 = caps(1, 0, Set.of());
        assertFalse(v10.supports("VK_KHR_get_memory_requirements2"),
                "Not core in Vulkan 1.0 and must not be assumed available");
        assertFalse(v10.supports("VK_KHR_synchronization2"));

        GpuCapabilities v11 = caps(1, 1, Set.of());
        assertTrue(v11.supports("VK_KHR_get_memory_requirements2"));
        assertFalse(v11.supports("VK_KHR_synchronization2"), "Requires Vulkan 1.3");
    }

    @Test
    @DisplayName("Non Promoted Extensions Still Require Advertising")
    void nonPromotedExtensionsStillRequireAdvertising() {
        // These three RT extensions are not core features and must be advertised explicitly.
        GpuCapabilities v14 = caps(1, 4, Set.of());
        assertFalse(v14.supports("VK_KHR_acceleration_structure"));
        assertFalse(v14.supports("VK_KHR_ray_query"));
        assertFalse(v14.supports("VK_KHR_ray_tracing_pipeline"));
        assertFalse(v14.supportsRayTracing(), "RT support requires advertised extensions");

        GpuCapabilities withRt = caps(1, 4,
                Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_query"));
        assertTrue(withRt.supportsRayTracing());
    }

    @Test
    @DisplayName("Typoed Names Are Not In The Table")
    void typoedNamesAreNotInTheTable() {
        GpuCapabilities v14 = caps(1, 4, Set.of());
        assertFalse(v14.supports("VK_KHR_get_memory_requirements_2"),
                "An extra underscore before 2 produces an unknown extension name");
        assertFalse(v14.supports("VK_KHR_get_physical_device_properties_2"));
    }

    @Test
    @DisplayName("Table Matches Authoritative Registry")
    void tableMatchesAuthoritativeRegistry() throws IOException {
        Path vkXml = findVkXml();
        assumeTrue(vkXml != null, "Vulkan SDK vk.xml unavailable; skipping registry comparison");

        String text = Files.readString(vkXml, StandardCharsets.UTF_8);

        Map<String, String> expectedDevice = new TreeMap<>();
        Map<String, String> expectedInstance = new TreeMap<>();
        Matcher m = EXT.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            String attrs = m.group(2);
            Matcher p = PROMOTED.matcher(attrs);
            if (!p.find()) {
                continue;
            }
            Matcher t = TYPE.matcher(attrs);
            if (!t.find()) {
                continue;
            }
            String version = p.group(2) + "." + p.group(3);
            if ("device".equals(t.group(1))) {
                expectedDevice.put(name, version);
            } else {
                expectedInstance.put(name, version);
            }
        }
        assumeTrue(!expectedDevice.isEmpty(), "No promotedto entries found in vk.xml; skipping an unrecognized registry format");

        Map<String, String> actualDevice = new TreeMap<>();
        for (String n : PromotedExtensions.deviceExtensions()) {
            actualDevice.put(n, PromotedExtensions.promotedTo(n).orElseThrow());
        }
        Map<String, String> actualInstance = new TreeMap<>();
        for (String n : PromotedExtensions.instanceExtensions()) {
            actualInstance.put(n, PromotedExtensions.promotedTo(n).orElseThrow());
        }

        assertEquals(expectedDevice, actualDevice,
                "Device promotion table differs from " + vkXml + ": "
                        + diff(expectedDevice, actualDevice));
        assertEquals(expectedInstance, actualInstance,
                "Instance promotion table differs from " + vkXml + ": "
                        + diff(expectedInstance, actualInstance));
    }

    private static String diff(Map<String, String> expected, Map<String, String> actual) {
        Map<String, String> missing = new LinkedHashMap<>(expected);
        missing.keySet().removeAll(actual.keySet());
        Map<String, String> extra = new LinkedHashMap<>(actual);
        extra.keySet().removeAll(expected.keySet());
        Map<String, String> wrong = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : actual.entrySet()) {
            String v = expected.get(e.getKey());
            if (v != null && !v.equals(e.getValue())) {
                wrong.put(e.getKey(), "表=" + e.getValue() + " 注册表=" + v);
            }
        }
        return "Missing " + missing + "; extra " + extra + "; version mismatch " + wrong;
    }

    /** Prefer VULKAN_SDK, then the latest installation under C:\VulkanSDK. */
    private static Path findVkXml() throws IOException {
        String env = System.getenv("VULKAN_SDK");
        if (env != null && !env.isBlank()) {
            Path p = Paths.get(env, "share", "vulkan", "registry", "vk.xml");
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        Path root = Paths.get("C:\\VulkanSDK");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .map(d -> d.resolve("share").resolve("vulkan").resolve("registry").resolve("vk.xml"))
                    .filter(Files::isRegularFile)
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .orElse(null);
        }
    }
}
