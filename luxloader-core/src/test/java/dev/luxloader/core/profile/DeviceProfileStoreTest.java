package dev.luxloader.core.profile;

import dev.luxloader.api.gpu.GpuCapabilities;
import dev.luxloader.api.gpu.GpuVendor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests device profile persistence across runs and separation by driver version. Corrupt files and
 * unwritable directories must degrade gracefully without preventing loader startup.
 */
class DeviceProfileStoreTest {

    @TempDir
    Path tempDir;

    /** Vulkan version encoding: major &lt;&lt; 22 | minor &lt;&lt; 12 | patch. */
    private static int vk(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

    private static GpuCapabilities caps(String name, int deviceId, int driverVersion, int apiVersion) {
        return new GpuCapabilities(name, GpuVendor.INTEL, 0x8086, deviceId, driverVersion,
                "测试驱动", apiVersion, java.util.Set.of("VK_KHR_swapchain"), java.util.Set.of(),
                1024, 49152, 1.0f, 16L * 1024 * 1024 * 1024, 16384);
    }

    /** Typical Intel Arc device capabilities for a particular driver. */
    private static GpuCapabilities arc(int driverVersion) {
        return caps("Intel(R) Arc(TM) A770 Graphics", 0x56A0, driverVersion, vk(1, 4, 356));
    }

    private Path file() {
        return tempDir.resolve("device-profiles.json");
    }

    /** Open the store and register a device to simulate one run. */
    private DeviceProfileStore runOnce(GpuCapabilities c) {
        DeviceProfileStore store = new DeviceProfileStore(file());
        store.record(c);
        store.save();
        return store;
    }

    @Test
    @DisplayName("Creates Profile On First Run")
    void createsProfileOnFirstRun() {
        DeviceProfileStore store = runOnce(arc(1663758));

        DeviceProfileStore.Profile p = store.currentProfile();
        assertNotNull(p, "Registering a device must create a current profile");
        assertEquals("Intel(R) Arc(TM) A770 Graphics", p.deviceName());
        assertEquals("Intel", p.vendor());
        assertEquals(0x56A0, p.deviceId());
        assertEquals(1663758, p.driverVersion());
        assertEquals("1.4.356", p.apiVersion());
        assertEquals(1, p.runCount(), "The first run count must be one");
        assertTrue(p.issues().isEmpty());
        assertEquals(1, store.size());
        assertTrue(Files.exists(file()), "Saving must create the file");
    }

    @Test
    @DisplayName("Accumulates Run Count")
    void accumulatesRunCount() throws Exception {
        DeviceProfileStore first = runOnce(arc(1663758));
        String firstSeen = first.currentProfile().firstSeen();

        // Reopen from disk with a new instance to verify persistence across runs.
        DeviceProfileStore second = new DeviceProfileStore(file());
        DeviceProfileStore.Profile p = second.record(arc(1663758));

        assertEquals(2, p.runCount(), "A second run must increment the count to two");
        assertEquals(firstSeen, p.firstSeen(), "Later runs must preserve the first-seen time");
        assertEquals(1, second.size(), "The same device must reuse its profile");
    }

    @Test
    @DisplayName("Different Driver Version Is Different Profile")
    void differentDriverVersionIsDifferentProfile() {
        runOnce(arc(1663758));

        DeviceProfileStore second = new DeviceProfileStore(file());
        DeviceProfileStore.Profile p = second.record(arc(9999999));

        assertEquals(1, p.runCount(), "A new driver version starts a new profile at one run");
        assertEquals(2, second.size(), "Different driver versions must have separate profiles");
        assertNotEquals(DeviceProfileStore.keyOf(arc(1663758)),
                DeviceProfileStore.keyOf(arc(9999999)),
                "The profile key must include the driver version");
    }

    @Test
    @DisplayName("Different Device Id Is Different Profile")
    void differentDeviceIdIsDifferentProfile() {
        runOnce(arc(1663758));

        DeviceProfileStore second = new DeviceProfileStore(file());
        second.record(caps("Intel(R) Arc(TM) A380 Graphics", 0x56A5, 1663758, vk(1, 4, 356)));

        assertEquals(2, second.size());
    }

    @Test
    @DisplayName("Accumulates Issues")
    void accumulatesIssues() {
        DeviceProfileStore first = new DeviceProfileStore(file());
        first.record(arc(1663758));
        first.noteIssue("compute_pipeline_null_handle", "第一次的描述");
        first.save();

        DeviceProfileStore second = new DeviceProfileStore(file());
        second.record(arc(1663758));
        second.noteIssue("compute_pipeline_null_handle", "第二次的描述");
        second.noteIssue("present_target_unavailable", "另一个问题");
        second.save();

        DeviceProfileStore third = new DeviceProfileStore(file());
        DeviceProfileStore.Profile p = third.record(arc(1663758));

        assertEquals(2, p.issues().get("compute_pipeline_null_handle").count(),
                "The same issue key must accumulate across runs");
        assertEquals("第二次的描述", p.issues().get("compute_pipeline_null_handle").note(),
                "Keep the most recent note");
        assertEquals(1, p.issues().get("present_target_unavailable").count());
        assertEquals(2, p.issues().size());
        assertTrue(p.issues().get("compute_pipeline_null_handle").firstSeen()
                        .compareTo(p.issues().get("compute_pipeline_null_handle").lastSeen()) <= 0,
                "firstSeen must not exceed lastSeen");
    }

    @Test
    @DisplayName("Note Issue Once Counts Once Per Run")
    void noteIssueOnceCountsOncePerRun() {
        DeviceProfileStore store = new DeviceProfileStore(file());
        store.record(arc(1663758));

        for (int i = 0; i < 1000; i++) {
            store.noteIssueOnce("present_target_unavailable", "每帧都会成立的条件");
        }
        assertEquals(1, store.currentProfile().issues().get("present_target_unavailable").count(),
                "One thousand calls must record one occurrence per run");

        // A new run record permits another occurrence to be recorded.
        store.record(arc(1663758));
        store.noteIssueOnce("present_target_unavailable", "每帧都会成立的条件");
        assertEquals(2, store.currentProfile().issues().get("present_target_unavailable").count(),
                "A new run may record another occurrence");
    }

    @Test
    @DisplayName("Note Issue Without Device Is Safe")
    void noteIssueWithoutDeviceIsSafe() {
        DeviceProfileStore store = new DeviceProfileStore(file());
        store.noteIssue("whatever", "还没接入设备就失败了");
        assertNull(store.currentProfile(), "No registered device means no current profile");
        assertTrue(store.all().isEmpty());
    }

    @Test
    @DisplayName("Unidentified Device Is Separate Profile")
    void unidentifiedDeviceIsSeparateProfile() {
        // Fallback capabilities when the host provides no vendor or device ID.
        GpuCapabilities unknown = new GpuCapabilities("未知设备（宿主未提供能力快照）",
                GpuVendor.UNKNOWN, 0, 0, 0, "", vk(1, 4, 356), java.util.Set.of(),
                java.util.Set.of(), 0, 0, 0f, 0L, 0);

        DeviceProfileStore store = new DeviceProfileStore(file());
        store.record(arc(1663758));
        store.record(unknown);

        assertEquals(2, store.size(),
                "An unidentified device must not merge with an identified device"
                        + " to avoid mixing unrelated histories");
        assertTrue(DeviceProfileStore.keyOf(unknown).startsWith("unidentified:"),
                DeviceProfileStore.keyOf(unknown));
    }

    @Test
    @DisplayName("Persists Capability Snapshot")
    void persistsCapabilitySnapshot() {
        DeviceProfileStore first = new DeviceProfileStore(file());
        first.record(arc(1663758));
        first.capabilities(Map.of(
                "host.vulkan_backend", "NATIVE",
                "host.present_intercept", "PARTIAL",
                "host.scene_extraction", "UNSUPPORTED"));
        first.save();

        DeviceProfileStore second = new DeviceProfileStore(file());
        DeviceProfileStore.Profile p = second.record(arc(1663758));

        assertEquals("NATIVE", p.capabilities().get("host.vulkan_backend"));
        assertEquals("PARTIAL", p.capabilities().get("host.present_intercept"));
        assertEquals("UNSUPPORTED", p.capabilities().get("host.scene_extraction"));
    }

    @Test
    @DisplayName("Corrupt File Does Not Throw")
    void corruptFileDoesNotThrow() throws Exception {
        Files.writeString(file(), "{ 这不是 json，是一段被手改坏的内容 ");

        DeviceProfileStore store = new DeviceProfileStore(file());

        assertTrue(store.all().isEmpty(), "A corrupt profile must recover to an empty store");
        assertFalse(store.warnings().isEmpty(), "Corrupt profiles must produce a warning");
        // Preserve the corrupt file by moving it aside for diagnosis.
        try (var list = Files.list(tempDir)) {
            assertTrue(list.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")),
                    "Back up corrupt profiles instead of overwriting them");
        }

        // The store must remain usable after recovery.
        DeviceProfileStore.Profile p = store.record(arc(1663758));
        assertEquals(1, p.runCount());
        assertTrue(store.save(), "The rebuilt store must save successfully");
    }

    @Test
    @DisplayName("Tolerates Wrong Field Types")
    void toleratesWrongFieldTypes() throws Exception {
        // Simulate malformed fields: a string runCount and non-object issue entries.
        Files.writeString(file(), """
                {
                  "version": 1,
                  "profiles": {
                    "0x8086:0x56A0:driver1663758:vk1.4.356": {
                      "deviceName": "手改过的设备",
                      "vendorId": 32902,
                      "deviceId": "22176",
                      "driverVersion": 1663758,
                      "apiVersion": "1.4.356",
                      "runCount": "7",
                      "issues": { "k": { "count": "3", "note": "手改的问题" }, "bad": 42 }
                    }
                  }
                }
                """);

        DeviceProfileStore store = new DeviceProfileStore(file());
        DeviceProfileStore.Profile p = store.find("0x8086:0x56A0:driver1663758:vk1.4.356").orElse(null);

        assertNotNull(p, "Preserve recognizable entries");
        assertEquals(7, p.runCount(), "Recognize integer values stored as strings");
        assertEquals(22176, p.deviceId());
        assertEquals(3, p.issues().get("k").count());
        assertEquals("手改的问题", p.issues().get("k").note());
        assertFalse(p.issues().containsKey("bad"), "Skip non-object entries");
    }

    @Test
    @DisplayName("Describes Current Profile With Issues")
    void describesCurrentProfileWithIssues() {
        DeviceProfileStore store = new DeviceProfileStore(file());
        store.record(arc(1663758));
        store.noteIssue("compute_pipeline_null_handle",
                "vkCreateComputePipelines 返回成功但句柄为 0（驱动异常）");

        String text = store.describeCurrent();

        assertTrue(text.contains("Intel(R) Arc(TM) A770 Graphics"), text);
        assertTrue(text.contains("0x56A0"), "The report must identify the GPU: " + text);
        assertTrue(text.contains("1.4.356"), text);
        assertTrue(text.contains("compute_pipeline_null_handle"), text);
        assertTrue(text.contains("驱动异常"), "Include the issue description: " + text);
    }

    @Test
    @DisplayName("Describes Current Profile Without Device")
    void describesCurrentProfileWithoutDevice() {
        DeviceProfileStore store = new DeviceProfileStore(file());
        assertTrue(store.describeCurrent().contains("尚未接入设备"), store.describeCurrent());
    }

    @Test
    @DisplayName("Save Failure Does Not Throw")
    void saveFailureDoesNotThrow() throws Exception {
        // Use a file as the parent path to force a write failure.
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "我是一个文件，不是目录");
        DeviceProfileStore store = new DeviceProfileStore(blocker.resolve("nested.json"));
        store.record(arc(1663758));

        assertFalse(store.save(), "An unwritable destination must return false");
        assertFalse(store.warnings().isEmpty(), "A warning must be recorded");
    }
}
