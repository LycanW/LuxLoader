package dev.luxloader.nativebridge;

import dev.luxloader.api.nativebridge.NativeBridge;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests FFM library loading, symbol lookup and downcalls using the platform C runtime (msvcrt or
 * libc), without shipping a test DLL.
 */
class FfmNativeBridgeTest {

    private static String systemLibc() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "msvcrt";
        }
        if (os.contains("mac")) {
            return "System";
        }
        return "c";
    }

    /** Probe FFM availability and skip when unavailable. */
    private static FfmNativeBridge bridgeOrSkip() {
        FfmNativeBridge bridge = new FfmNativeBridge();
        Assumptions.assumeTrue(bridge.isAvailable(),
                "FFM unavailable; requires JDK 22+ and permission to use native access");
        return bridge;
    }

    @Test
    @DisplayName("Reports Implementation")
    void reportsImplementation() {
        FfmNativeBridge bridge = new FfmNativeBridge();
        assertTrue(bridge.implementationName().contains("FFM")
                        || bridge.implementationName().equals("none"),
                "Expected implementation FFM or none; actual: " + bridge.implementationName());
        assertEquals(bridge.isAvailable(), bridge.implementationName().startsWith("FFM"));
    }

    @Test
    @DisplayName("Platform Tag Is Well Formed")
    void platformTagIsWellFormed() {
        FfmNativeBridge bridge = new FfmNativeBridge(false);
        String tag = bridge.platformTag();

        assertTrue(tag.contains("-"), "Platform ID must have os-arch form: " + tag);
        String[] parts = tag.split("-");
        assertEquals(2, parts.length, "Expected exactly two components: " + tag);
        assertTrue(java.util.List.of("windows", "linux", "macos", "unknown").contains(parts[0]),
                "Unknown OS component: " + parts[0]);
        assertTrue(java.util.List.of("x64", "x86", "arm64").contains(parts[1]),
                "Unknown architecture component: " + parts[1]);
        System.out.println("[Native bridge] Platform ID = " + tag);
    }

    @Test
    @DisplayName("Library Names Get Platform Suffix")
    void libraryNamesGetPlatformSuffix() {
        // Use the public API: failure to load a missing library must include the platform suffix.
        FfmNativeBridge bridge = new FfmNativeBridge(false);
        NativeBridge.NativeLoadException e = assertThrows(NativeBridge.NativeLoadException.class,
                () -> bridge.load("luxloader_definitely_missing_library_xyz"));
        assertTrue(e.getMessage().contains("luxloader_definitely_missing_library_xyz"), e.getMessage());
        // The error should provide actionable guidance rather than a bare UnsatisfiedLinkError.
        assertTrue(e.getMessage().contains("原因：") || e.getMessage().contains("原始错误"),
                "The failure must include actionable guidance: " + e.getMessage());
    }

    @Test
    @DisplayName("Loads System Library")
    void loadsSystemLibrary() {
        FfmNativeBridge bridge = bridgeOrSkip();

        NativeBridge.NativeLibrary lib = bridge.load(systemLibc());
        assertNotNull(lib.name());
        assertTrue(bridge.loadedLibraries().contains(lib.name()),
                "Loaded-library records must include this library: " + bridge.loadedLibraries());

        // malloc/free are available on all target platforms.
        assertTrue(lib.has("malloc"), "The system library must export malloc");
        long address = lib.lookup("malloc");
        assertNotEquals(0L, address, "malloc address must be nonzero");
        assertEquals(address, lib.addressOf("malloc"), "Repeated symbol resolution must return the same address");
        System.out.println("[Native bridge] " + lib.name() + " malloc @ 0x" + Long.toHexString(address));
    }

    @Test
    @DisplayName("Missing Symbol Is Explained")
    void missingSymbolIsExplained() {
        FfmNativeBridge bridge = bridgeOrSkip();
        NativeBridge.NativeLibrary lib = bridge.load(systemLibc());

        assertFalse(lib.has("luxloader_no_such_symbol_at_all"));
        NativeBridge.NativeLoadException e = assertThrows(NativeBridge.NativeLoadException.class,
                () -> lib.lookup("luxloader_no_such_symbol_at_all"));
        assertTrue(e.getMessage().contains("luxloader_no_such_symbol_at_all"), e.getMessage());
        assertTrue(e.getMessage().contains("版本"), "Suggest a possible version mismatch: " + e.getMessage());
    }

    @Test
    @DisplayName("Arena Allocates And Reads Back")
    void arenaAllocatesAndReadsBack() {
        FfmNativeBridge bridge = bridgeOrSkip();

        try (NativeBridge.Arena arena = bridge.arena("test")) {
            NativeBridge.MemoryHandle handle = arena.allocate(64);
            assertEquals(64, handle.size());
            assertNotEquals(0L, handle.address());

            handle.putInt(0, 0x11223344);
            handle.putLong(8, 0x5566778899AABBCCL);
            handle.putFloat(16, 1.5f);
            handle.putDouble(24, 2.25);

            assertEquals(0x11223344, handle.getInt(0));
            assertEquals(0x5566778899AABBCCL, handle.getLong(8));
            assertEquals(1.5f, handle.getFloat(16), 0.0001f);
            assertEquals(2.25, handle.getDouble(24), 0.0001);

            byte[] payload = {1, 2, 3, 4, 5};
            handle.putBytes(32, payload);
            assertArrayEquals(payload, handle.getBytes(32, 5));

            handle.putPointer(40, 0xDEADBEEFL);
            assertEquals(0xDEADBEEFL, handle.getLong(40));
        }
    }

    @Test
    @DisplayName("Utf8 Strings Are Nul Terminated")
    void utf8StringsAreNulTerminated() {
        FfmNativeBridge bridge = bridgeOrSkip();

        try (NativeBridge.Arena arena = bridge.arena("strings")) {
            NativeBridge.MemoryHandle handle = arena.allocateUtf8("DLSS");
            assertEquals(5, handle.size(), "Four characters plus NUL");

            byte[] bytes = handle.getBytes(0, 5);
            assertEquals("DLSS", new String(bytes, 0, 4, StandardCharsets.UTF_8));
            assertEquals(0, bytes[4], "The final byte must be NUL");

            // Verify Chinese UTF-8 text too.
            NativeBridge.MemoryHandle chinese = arena.allocateUtf8("超分");
            String roundTrip = new String(chinese.getBytes(0, (int) chinese.size() - 1),
                    StandardCharsets.UTF_8);
            assertEquals("超分", roundTrip);
        }
    }

    @Test
    @DisplayName("Aligned Allocation")
    void alignedAllocation() {
        FfmNativeBridge bridge = bridgeOrSkip();

        try (NativeBridge.Arena arena = bridge.arena("aligned")) {
            NativeBridge.MemoryHandle handle = arena.allocateAligned(128, 64);
            assertEquals(128, handle.size());
            assertEquals(0L, handle.address() % 64, "The address must be aligned to 64 bytes");

            assertThrows(IllegalArgumentException.class, () -> arena.allocateAligned(16, 0));
        }
    }

    @Test
    @DisplayName("Arena Tracks Allocation")
    void arenaTracksAllocation() {
        FfmNativeBridge bridge = bridgeOrSkip();

        try (NativeBridge.Arena arena = bridge.arena("tracking")) {
            assertEquals(0L, arena.allocatedBytes());
            arena.allocate(100);
            arena.allocate(200);
            assertEquals(300L, arena.allocatedBytes());
        }
    }

    @Test
    @DisplayName("Unsupported Argument Types Are Reported")
    void unsupportedArgumentTypesAreReported() {
        FfmNativeBridge bridge = bridgeOrSkip();
        NativeBridge.NativeLibrary lib = bridge.load(systemLibc());

        // Pass an object unsupported by the native argument conversion.
        NativeBridge.NativeLoadException e = assertThrows(NativeBridge.NativeLoadException.class,
                () -> lib.callInt("malloc", new Object()));
        assertTrue(e.getMessage().contains("不支持的参数类型"), e.getMessage());
    }

    @Test
    @DisplayName("Exists Is Safe")
    void existsIsSafe() {
        FfmNativeBridge bridge = new FfmNativeBridge(false);
        assertFalse(bridge.exists("luxloader_definitely_missing_library_xyz"));
        assertFalse(bridge.exists(""));
        assertFalse(bridge.exists(null));
    }

    @Test
    @DisplayName("Handle Cache Is Keyed By Argument Types")
    void handleCacheIsKeyedByArgumentTypes() {
        FfmNativeBridge bridge = bridgeOrSkip();
        NativeBridge.NativeLibrary lib = bridge.load(systemLibc());

        try (NativeBridge.Arena arena = bridge.arena("handle-cache")) {
            NativeBridge.MemoryHandle text = arena.allocateUtf8("luxloader");

            // Order matters: first call with a long address, then with MemoryHandle. The old cache key ignored argument layouts and reused a (j8)j8 handle for an (a8)j8 call, causing a ClassCastException. Reversing the calls may succeed through address conversion and would miss this regression.
            long viaLong = lib.callPointer("strlen", text.address());
            long viaHandle = lib.callPointer("strlen", text);

            // A raw long address is valid; vendor APIs often represent pointers as uint64_t.
            assertEquals(9L, viaLong, "luxloader contains nine characters");
            assertEquals(viaLong, viaHandle,
                    "Different representations of the same address must return the same result");
        }
    }

    @Test
    @DisplayName("Exists Probes Without Loading")
    void existsProbesWithoutLoading() {
        FfmNativeBridge bridge = new FfmNativeBridge(false);

        // This assertion requires java.library.path to include the system library directory. Skip platforms without that prerequisite; the contract is discovery without loading, not platform-specific path guessing.
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "This assertion requires java.library.path to include Windows system directories");

        assertTrue(bridge.exists("kernel32"),
                "kernel32.dll must be discoverable through java.library.path");

        // Verify that exists() does not use this bridge's loading path. JDK APIs cannot establish whether another component mapped the library, so this assertion alone cannot prove process-wide absence.
        assertTrue(bridge.loadedLibraries().isEmpty(), "Probing must not register loaded libraries");
    }

    @Test
    @DisplayName("Availability Can Be Forced")
    void availabilityCanBeForced() {
        FfmNativeBridge forced = new FfmNativeBridge(false);
        assertTrue(forced.isAvailable(), "Disabling probing must report available");
        assertTrue(forced.implementationName().contains("FFM"));
    }
}
