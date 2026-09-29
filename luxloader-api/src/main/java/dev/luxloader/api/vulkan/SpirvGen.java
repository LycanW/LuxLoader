package dev.luxloader.api.vulkan;

import static dev.luxloader.api.i18n.Messages.tr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * SPIR-V structural validation and dumps before driver submission. Checks magic, version, ID bound and
 * instruction word counts to reject common malformed modules that can crash drivers/JVMs. This is not
 * full semantic validation; use spirv-val in builds. Includes limited semantic inspection such as
 * localSize for contract tests. Built-in fill/blend shaders are compiled by slangc from
 * src/main/slang, never assembled at runtime.
 */
public final class SpirvGen {

    private SpirvGen() {
    }

    // Runtime SPIR-V assembly was removed after incorrect word counts caused driver access violations and mismatched LocalSize escaped structural checks. Compile built-in shaders with slangc; retain only validation, dumps and limited semantic inspection here.
    /** Wraps SPIR-V in a little-endian ByteBuffer for LWJGL pCode. */
    public static ByteBuffer asBuffer(byte[] spirv) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(spirv.length).order(ByteOrder.nativeOrder());
        buffer.put(spirv).flip();
        return buffer;
    }

    /** SPIR-V structural validation result. */
    public record Validation(boolean valid, String error, int instructionCount, int declaredBound) {

        public static Validation ok(int instructions, int bound) {
            return new Validation(true, "", instructions, bound);
        }

        public static Validation fail(String error) {
            return new Validation(false, error, 0, 0);
        }
    }

    /** SPIR-V header magic word. */
    public static final int MAGIC = 0x07230203;

    /**
     * Checks structural SPIR-V consistency before driver submission: magic, version, ID bound and
     * instruction word counts. Malformed modules can crash drivers and the JVM, so reject them with an
     * actionable error. Full semantic validation requires spirv-val.
     * @param spirv SPIR-V binary
     */
    public static Validation validate(byte[] spirv) {
        if (spirv == null || spirv.length == 0) {
            return Validation.fail(tr("SPIR-V data is empty"));
        }
        if (spirv.length % 4 != 0) {
            return Validation.fail(tr("SPIR-V length must be a multiple of 4, got ") + spirv.length + tr("SpirvGen.f5d87a8b74", " bytes"));
        }
        if (spirv.length < 20) {
            return Validation.fail(tr("SPIR-V requires a header of at least 5 words, got ") + spirv.length + tr("SpirvGen.f5d87a8b74", " bytes"));
        }

        ByteBuffer buf = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            return Validation.fail(String.format(tr("Invalid magic: expected 0x%08X, got 0x%08X"), MAGIC, magic));
        }
        int version = buf.getInt();
        int major = (version >>> 16) & 0xFF;
        int minor = (version >>> 8) & 0xFF;
        if (major != 1 || minor > 6) {
            return Validation.fail(tr("Unsupported SPIR-V version ") + major + "." + minor + tr(" (supported: 1.0 through 1.6)"));
        }
        buf.getInt(); // Generator magic accepts any value.
        int bound = buf.getInt();
        buf.getInt(); // Reserved field must be zero according to the specification.

        if (bound <= 0) {
            return Validation.fail(tr("ID bound must be positive, got ") + bound);
        }

        int words = spirv.length / 4;
        int offset = 5;
        int instructionCount = 0;
        while (offset < words) {
            // Read header fields by absolute offset, independent of buffer position.
            int header = buf.getInt(offset * 4);
            int wordCount = (header >>> 16) & 0xFFFF;
            int opcode = header & 0xFFFF;
            if (wordCount == 0) {
                return Validation.fail(tr("Instruction ") + instructionCount + tr(" (offset ") + offset
                        + tr(") has zero words and would cause an infinite loop"));
            }
            if (offset + wordCount > words) {
                return Validation.fail(tr("Instruction ") + instructionCount + tr(" (opcode=") + opcode
                        + tr(", offset ") + offset + tr(") declares ") + wordCount + tr(" words, but the module has only ")
                        + (words - offset) + tr(" words remaining"));
            }
            offset += wordCount;
            instructionCount++;
        }
        if (offset != words) {
            return Validation.fail(tr("Instruction length differs from module size (final offset ") + offset + tr(" / total words ") + words + "）");
        }
        return Validation.ok(instructionCount, bound);
    }

    /** Checks header plausibility for tests. */
    public static boolean looksValid(byte[] spirv) {
        return validate(spirv).valid();
    }

    /** OpExecutionMode opcode. */
    private static final int OP_EXECUTION_MODE = 16;

    /** LocalSize execution mode value. */
    private static final int EXECUTION_MODE_LOCAL_SIZE = 17;

    /**
     * Decodes compute workgroup dimensions for semantic contract tests. Structural validation cannot
     * detect disagreement between shader LocalSize and Java constants; compare them explicitly.
     * @param spirv SPIR-V binary
     * @return {x,y,z}, or null without a LocalSize declaration
     */
    public static int[] localSize(byte[] spirv) {
        if (spirv == null || spirv.length < 20 || !validate(spirv).valid()) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        int words = spirv.length / 4;
        int offset = 5;
        while (offset < words) {
            int header = buf.getInt(offset * 4);
            int wordCount = (header >>> 16) & 0xFFFF;
            int opcode = header & 0xFFFF;
            if (wordCount == 0) {
                return null;
            }
            // OpExecutionMode %entry <mode> <literals...>: LocalSize has three literals and six words total.
            if (opcode == OP_EXECUTION_MODE && wordCount >= 6
                    && buf.getInt((offset + 2) * 4) == EXECUTION_MODE_LOCAL_SIZE) {
                return new int[] {
                        buf.getInt((offset + 3) * 4),
                        buf.getInt((offset + 4) * 4),
                        buf.getInt((offset + 5) * 4)
                };
            }
            offset += wordCount;
        }
        return null;
    }

    /** Produces an instruction dump to locate malformed instructions reported by validate(). */
    public static String describe(byte[] spirv) {
        if (spirv == null || spirv.length < 5) {
            return tr("<invalid module>");
        }
        ByteBuffer buf = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(tr("Header: magic=0x%08X version=0x%08X bound=%d words=%d%n"),
                buf.getInt(0), buf.getInt(4), buf.getInt(12), spirv.length / 4));
        int words = spirv.length / 4;
        int offset = 5;
        int index = 0;
        while (offset < words && index < 64) {
            int word = buf.getInt(offset * 4);
            int count = (word >>> 16) & 0xFFFF;
            int opcode = word & 0xFFFF;
            sb.append(String.format("  #%d @word%-4d opcode=%-5d words=%d%n", index, offset, opcode, count));
            if (count == 0) {
                sb.append(tr("      ^ Zero word count; module is corrupted here")).append(System.lineSeparator());
                break;
            }
            offset += count;
            index++;
        }
        return sb.toString();
    }
}
