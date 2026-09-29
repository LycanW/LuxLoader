package dev.luxloader.api.vulkan;

import static dev.luxloader.api.i18n.Messages.tr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Internal SPIR-V resource loading and header inspection shared by the fill/blend modules. Centralizes
 * structural validation, bound/word counts and actionable errors. Package-private rather than plugin
 * API.
 */
final class ModuleResources {

    private ModuleResources() {
    }

    /**
     * Reads and structurally validates a classpath SPIR-V module. Throws on missing/invalid resources
     * rather than forwarding an empty module to the driver.
     * @param resource absolute classpath path beginning with /
     * @param sourceHint shader source path for error messages
     */
    static byte[] load(String resource, String sourceHint) {
        try (InputStream in = ModuleResources.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(missingMessage(resource, sourceHint, tr("Resource not found")));
            }
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) {
                throw new IllegalStateException(missingMessage(resource, sourceHint, tr("Resource is empty")));
            }
            SpirvGen.Validation validation = SpirvGen.validate(bytes);
            if (!validation.valid()) {
                throw new IllegalStateException(
                        tr("Invalid built-in SPIR-V structure (") + resource + "）: " + validation.error()
                                + System.lineSeparator() + SpirvGen.describe(bytes));
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException(
                    missingMessage(resource, sourceHint, tr("Read failed: ") + e), e);
        }
    }

    /**
     * Reads the ID bound at byte offset 12 (fourth word).
     * @param spirv loaded module
     * @return bound, or zero for a truncated module
     */
    static int readBound(byte[] spirv) {
        if (spirv == null || spirv.length < 16) {
            return 0;
        }
        return ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
    }

    private static String missingMessage(String resource, String sourceHint, String what) {
        return tr("Built-in SPIR-V ") + what + ": " + resource
                + tr(". Shader source is in ") + sourceHint
                + tr("; regenerate with `gradlew :luxloader-api:compileShaders` ")
                + tr("and ensure src/main/resources/spirv/ is packaged in the JAR.");
    }
}
