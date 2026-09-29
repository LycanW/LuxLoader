package dev.luxloader.core.vulkan;

import dev.luxloader.api.vulkan.ComputeBlendModule;
import dev.luxloader.api.vulkan.ComputeFillModule;
import dev.luxloader.api.vulkan.SpirvGen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dumps compiled built-in SPIR-V for diagnostics, complementing contract assertions in SpirvGenTest
 * and ComputeBlendModuleTest. Reads build-time slangc output without generating modules.
 */
class SpirvGenDiagnosticsTest {

    /**
     * Names for common opcodes improve readability. Unknown values fall back to opcodeN without requiring
     * a complete version-specific table.
     */
    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case 3 -> "OpSource";
            case 5 -> "OpName";
            case 14 -> "OpMemoryModel";
            case 15 -> "OpEntryPoint";
            case 16 -> "OpExecutionMode";
            case 17 -> "OpCapability";
            case 19 -> "OpTypeVoid";
            case 21 -> "OpTypeInt";
            case 22 -> "OpTypeFloat";
            case 23 -> "OpTypeVector";
            case 25 -> "OpTypeImage";
            case 26 -> "OpTypeSampler";
            case 27 -> "OpTypeSampledImage";
            case 29 -> "OpTypeRuntimeArray";
            case 30 -> "OpTypeStruct";
            case 32 -> "OpTypePointer";
            case 33 -> "OpTypeFunction";
            case 43 -> "OpConstant";
            case 44 -> "OpConstantComposite";
            case 54 -> "OpFunction";
            case 56 -> "OpFunctionEnd";
            case 59 -> "OpVariable";
            case 61 -> "OpLoad";
            case 62 -> "OpStore";
            case 65 -> "OpAccessChain";
            case 71 -> "OpDecorate";
            case 72 -> "OpMemberDecorate";
            case 81 -> "OpCompositeExtract";
            case 95 -> "OpImageFetch";
            case 98 -> "OpImageRead";
            case 99 -> "OpImageWrite";
            case 128 -> "OpIAdd";
            case 132 -> "OpIMul";
            case 248 -> "OpLabel";
            case 249 -> "OpSelectionMerge";
            case 250 -> "OpBranchConditional";
            case 253 -> "OpReturn";
            default -> "opcode" + opcode;
        };
    }

    /** Summarize contract-relevant module header fields. */
    private static String summary(byte[] spirv) {
        ByteBuffer buf = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        int[] localSize = SpirvGen.localSize(spirv);
        return String.format("magic=0x%08X version=0x%08X bound=%d %d words   LocalSize=%s%n",
                buf.getInt(0), buf.getInt(4), buf.getInt(12), spirv.length / 4,
                localSize == null ? "<none>" : localSize[0] + "x" + localSize[1] + "x" + localSize[2]);
    }

    private static void dump(String title, byte[] spirv, int maxInstructions) {
        ByteBuffer buf = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(title).append(" ===").append(System.lineSeparator());
        sb.append(summary(spirv));

        int words = spirv.length / 4;
        int offset = 5;
        int index = 0;
        while (offset < words && index < maxInstructions) {
            int header = buf.getInt(offset * 4);
            int count = (header >>> 16) & 0xFFFF;
            int opcode = header & 0xFFFF;
            StringBuilder operands = new StringBuilder();
            for (int i = 1; i < count && offset + i < words; i++) {
                operands.append(String.format(" %d", buf.getInt((offset + i) * 4)));
            }
            sb.append(String.format("  #%-2d word%-4d %-22s words=%-2d operands:%s%n",
                    index, offset, opcodeName(opcode), count, operands));
            if (count == 0) {
                sb.append("      ^ zero word count; the module is corrupt and parsing cannot continue")
                        .append(System.lineSeparator());
                break;
            }
            offset += count;
            index++;
        }
        sb.append("Parsing ended at word ").append(offset).append(" / ").append(words)
                .append(offset == words ? " (complete)" : " (inconsistent)");
        System.out.println(sb);
    }

    @Test
    @DisplayName("Dump Fill Head")
    void dumpFillHead() {
        dump("fill.spv instruction headers", ComputeFillModule.bytes(), 14);
    }

    @Test
    @DisplayName("Dump Fill Tail")
    void dumpFillTail() {
        byte[] spirv = ComputeFillModule.bytes();
        ByteBuffer buf = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        int words = spirv.length / 4;
        int offset = 5;
        int functionWord = -1;
        while (offset < words) {
            int header = buf.getInt(offset * 4);
            int count = (header >>> 16) & 0xFFFF;
            if (count == 0) {
                break;
            }
            if ((header & 0xFFFF) == 54 && functionWord < 0) {
                functionWord = offset;
            }
            offset += count;
        }
        // Fail explicitly if the function body is missing instead of reading at cursor -1.
        assertTrue(functionWord >= 0, "Missing OpFunction in a module of " + words + " words");
        System.out.println("OpFunction starts at word " + functionWord + "; total module words = " + words);

        int cursor = functionWord;
        int index = 0;
        while (cursor < words) {
            int header = buf.getInt(cursor * 4);
            int count = (header >>> 16) & 0xFFFF;
            int opcode = header & 0xFFFF;
            StringBuilder operands = new StringBuilder();
            for (int i = 1; i < count && cursor + i < words; i++) {
                operands.append(String.format(" %d", buf.getInt((cursor + i) * 4)));
            }
            System.out.printf("  #%-2d word%-4d %-22s words=%-2d operands:%s%n",
                    index, cursor, opcodeName(opcode), count, operands);
            if (count == 0) {
                System.out.println("      ^ corrupt");
                break;
            }
            cursor += count;
            index++;
        }
    }

    @Test
    @DisplayName("Dump Blend Head")
    void dumpBlendHead() {
        dump("blend.spv instruction headers", ComputeBlendModule.bytes(), 24);
    }
}
