package dev.luxloader.shader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests bundled compiler extraction with a small fixture instead of shipping a real compiler.
 * extractBundled's platform parameter selects the fixture; production continues using the actual
 * platform.
 */
class SlangToolchainTest {

    /** Fixture platform matching src/test/resources/native/test-fixture/. */
    private static final String FIXTURE = "test-fixture";

    @TempDir
    Path tempDir;

    private Optional<SlangToolchain.Resolved> extract() {
        return SlangToolchain.extractBundled(tempDir, FIXTURE, SlangToolchainTest.class);
    }

    private Path extractedDir() {
        return tempDir.resolve("tools").resolve("slang").resolve(FIXTURE + "-9.9.9-test");
    }

    @Test
    @DisplayName("Extracts On First Use")
    void extractsOnFirstUse() throws IOException {
        Optional<SlangToolchain.Resolved> resolved = extract();

        assertTrue(resolved.isPresent(), "The fixture exists on the test classpath and must extract successfully");
        SlangToolchain.Resolved toolchain = resolved.orElseThrow();
        assertEquals(SlangToolchain.Resolved.Origin.BUNDLED, toolchain.origin());
        assertEquals(extractedDir(), toolchain.libraryDir());

        assertTrue(Files.isRegularFile(extractedDir().resolve("slangc")));
        assertTrue(Files.isRegularFile(extractedDir().resolve("libfake-slang.so")),
                "Extract dependencies together with the executable");
        assertTrue(Files.isRegularFile(extractedDir().resolve("manifest.properties")),
                "Write the completion manifest last");

        assertEquals("FAKE-SLANGC-PAYLOAD-v1",
                Files.readString(extractedDir().resolve("slangc"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Reports Version From Manifest")
    void reportsVersionFromManifest() {
        SlangToolchain.Resolved toolchain = extract().orElseThrow();

        assertEquals("9.9.9-test", toolchain.version(),
                "If the fixture cannot report -v, use the manifest version");
        assertTrue(toolchain.describe().contains("9.9.9-test"), toolchain.describe());
        assertTrue(toolchain.describe().contains("jar 内捆绑"), toolchain.describe());
    }

    @Test
    @DisplayName("Directory Name Carries Version")
    void directoryNameCarriesVersion() {
        extract();
        assertTrue(Files.isDirectory(tempDir.resolve("tools/slang/" + FIXTURE + "-9.9.9-test")),
                "Include the version in the extraction directory name");
    }

    @Test
    @DisplayName("Marks Executable")
    void marksExecutable() throws IOException {
        extract();
        Path executable = extractedDir().resolve("slangc");

        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(executable);
            assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE),
                    "Set executable permission so the compiler subprocess can start");
        } catch (UnsupportedOperationException e) {
            // Windows uses executable suffixes rather than POSIX permission bits.
            assertTrue(executable.toFile().canExecute() || System.getProperty("os.name")
                    .toLowerCase(java.util.Locale.ROOT).contains("win"));
        }
    }

    @Test
    @DisplayName("Does Not Mark Libraries Executable")
    void doesNotMarkLibrariesExecutable() throws IOException {
        extract();
        Path library = extractedDir().resolve("libfake-slang.so");
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(library);
            assertFalse(perms.contains(PosixFilePermission.OWNER_EXECUTE),
                    "Shared libraries are not executable programs");
        } catch (UnsupportedOperationException e) {
            // This branch is not reached on Windows.
        }
    }

    @Test
    @DisplayName("Second Call Does Not Reextract")
    void secondCallDoesNotReextract() throws IOException {
        extract();

        // Modify the extracted file to detect unnecessary re-extraction on the second call.
        Path marker = extractedDir().resolve("libfake-slang.so");
        Files.writeString(marker, "CHANGED-BY-TEST", StandardCharsets.UTF_8);

        extract();

        assertEquals("CHANGED-BY-TEST", Files.readString(marker, StandardCharsets.UTF_8),
                "The second call must reuse the extracted distribution");
    }

    @Test
    @DisplayName("Reextracts When Version Differs")
    void reextractsWhenVersionDiffers() throws IOException {
        extract();

        Path target = extractedDir().resolve("slangc");
        Files.writeString(target, "STALE", StandardCharsets.UTF_8);
        // Change the on-disk manifest version to simulate an older extracted distribution.
        Files.writeString(extractedDir().resolve("manifest.properties"),
                "slang.version=0.0.1-old\nfiles=slangc,libfake-slang.so\n",
                StandardCharsets.UTF_8);

        extract();

        assertEquals("FAKE-SLANGC-PAYLOAD-v1", Files.readString(target, StandardCharsets.UTF_8),
                "Version mismatches must trigger re-extraction");
    }

    @Test
    @DisplayName("Reextracts When File Missing")
    void reextractsWhenFileMissing() throws IOException {
        extract();
        Files.delete(extractedDir().resolve("libfake-slang.so"));

        assertTrue(extract().isPresent());
        assertTrue(Files.isRegularFile(extractedDir().resolve("libfake-slang.so")),
                "Restore missing files after partial extraction");
    }

    @Test
    @DisplayName("Missing Resources Are Harmless")
    void missingResourcesAreHarmless() {
        assertTrue(SlangToolchain.extractBundled(tempDir, "no-such-platform", SlangToolchainTest.class)
                .isEmpty());
        assertTrue(SlangToolchain.extractBundled(tempDir, "test-fixture", String.class).isEmpty(),
                "An anchor without the requested resource must return empty quietly");
    }

    @Test
    @DisplayName("Platform And Executable Name Match")
    void platformAndExecutableNameMatch() {
        assertTrue(SlangToolchain.platform().contains("-"),
                "Expected a platform ID such as windows-x86_64: " + SlangToolchain.platform());
        assertEquals("slangc.exe", SlangToolchain.executableName("windows-x86_64"));
        assertEquals("slangc.exe", SlangToolchain.executableName("windows-aarch64"));
        assertEquals("slangc", SlangToolchain.executableName("linux-x86_64"));
        assertEquals("slangc", SlangToolchain.executableName("macos-aarch64"));
        assertNotEquals("", SlangToolchain.executableName());
    }

    @Test
    @DisplayName("Library Path Environment")
    void libraryPathEnvironment() {
        Path dir = tempDir.resolve("libs");
        var env = SlangToolchain.libraryPathEnv(dir);

        String platform = SlangToolchain.platform();
        if (platform.startsWith("windows")) {
            assertTrue(env.isEmpty(),
                    "Windows searches for DLLs beside the executable without extra configuration");
        } else if (platform.startsWith("linux")) {
            assertTrue(env.get("LD_LIBRARY_PATH").startsWith(dir.toAbsolutePath().toString()));
        } else {
            assertTrue(env.get("DYLD_LIBRARY_PATH").startsWith(dir.toAbsolutePath().toString()));
        }
    }

    @Test
    @DisplayName("Default Cache Root Is Overridable")
    void defaultCacheRootIsOverridable() {
        String previous = System.getProperty("luxloader.cacheDir");
        try {
            System.setProperty("luxloader.cacheDir", tempDir.toString());
            assertEquals(tempDir, SlangToolchain.defaultCacheRoot());

            System.clearProperty("luxloader.cacheDir");
            assertTrue(SlangToolchain.defaultCacheRoot().toString().contains(".luxloader"),
                    "The default location must be under the user directory: " + SlangToolchain.defaultCacheRoot());
        } finally {
            if (previous == null) {
                System.clearProperty("luxloader.cacheDir");
            } else {
                System.setProperty("luxloader.cacheDir", previous);
            }
        }
    }
}
