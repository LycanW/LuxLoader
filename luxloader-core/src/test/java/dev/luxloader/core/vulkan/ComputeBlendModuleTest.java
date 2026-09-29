package dev.luxloader.core.vulkan;

import dev.luxloader.api.vulkan.ComputeBlendModule;
import dev.luxloader.api.vulkan.SpirvGen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the compiled blend SPIR-V contract: valid structure, main compute entry point, LocalSize
 * matching Java, bindings 0/1/2, a 16-byte push-constant block with blend factor at offset zero, and
 * input/output image access. Assert interface contracts rather than compiler-specific instruction
 * sequences. Historical handwritten modules passed weak structural checks despite incorrect opcodes
 * and workgroup constants.
 */
class ComputeBlendModuleTest {

    // Named SPIR-V enumerants avoid confusing BufferBlock (3) with DescriptorSet (34), or ArrayStride (6) with Offset (35).
    private static final int OP_ENTRY_POINT = 15;
    private static final int OP_EXECUTION_MODE = 16;
    private static final int OP_DECORATE = 71;
    private static final int OP_MEMBER_DECORATE = 72;
    private static final int OP_IMAGE_FETCH = 95;
    private static final int OP_IMAGE_READ = 98;
    private static final int OP_IMAGE_WRITE = 99;

    private static final int EXECUTION_MODEL_GLCOMPUTE = 5;
    private static final int EXECUTION_MODE_LOCAL_SIZE = 17;
    private static final int DECORATION_BLOCK = 2;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int DECORATION_OFFSET = 35;

    private static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Instruction word offset, opcode and operands. */
    private record Instruction(int offset, int opcode, int[] operands) {
    }

    /** Decode instructions and verify traversal covers the module exactly. */
    private static List<Instruction> disassemble(byte[] spirv) {
        ByteBuffer buf = le(spirv);
        int words = spirv.length / 4;
        List<Instruction> out = new ArrayList<>();
        int offset = 5;
        while (offset < words) {
            int header = buf.getInt(offset * 4);
            int count = (header >>> 16) & 0xFFFF;
            if (count == 0) {
                fail("Compilation " + out.size() + " instruction at word " + offset + " has zero word count; the module is corrupt");
            }
            int[] operands = new int[count - 1];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = buf.getInt((offset + 1 + i) * 4);
            }
            out.add(new Instruction(offset, header & 0xFFFF, operands));
            offset += count;
        }
        assertEquals(words, offset, "Instruction traversal must cover the entire module");
        return out;
    }

    /** Decode the OpEntryPoint name. */
    private static String entryPointName(List<Instruction> instructions) {
        for (Instruction instruction : instructions) {
            if (instruction.opcode() != OP_ENTRY_POINT) {
                continue;
            }
            int[] operands = instruction.operands();
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < operands.length; i++) {
                int word = operands[i];
                boolean terminated = false;
                for (int shift = 0; shift < 32; shift += 8) {
                    int b = (word >>> shift) & 0xFF;
                    if (b == 0) {
                        terminated = true;
                        break;
                    }
                    sb.append((char) b);
                }
                if (terminated) {
                    break;
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * Collect target/value pairs for a decoration. OpDecorate has variable operand counts: Block has only
     * target and decoration, while Binding adds a value. Accept two operands and use -1 for absent values.
     */
    private static List<int[]> decorations(List<Instruction> instructions, int decoration) {
        List<int[]> out = new ArrayList<>();
        for (Instruction instruction : instructions) {
            if (instruction.opcode() != OP_DECORATE || instruction.operands().length < 2) {
                continue;
            }
            int[] operands = instruction.operands();
            if (operands[1] == decoration) {
                out.add(new int[] {operands[0], operands.length >= 3 ? operands[2] : -1});
            }
        }
        return out;
    }

    @Test
    @DisplayName("Module Is Structurally Valid")
    void moduleIsStructurallyValid() {
        byte[] spirv = ComputeBlendModule.bytes();
        SpirvGen.Validation validation = SpirvGen.validate(spirv);

        assertTrue(validation.valid(),
                "Invalid structure: " + validation.error() + System.lineSeparator() + SpirvGen.describe(spirv));
        assertEquals(ComputeBlendModule.DECLARED_BOUND, validation.declaredBound(),
                "The ID bound must match the header-derived constant");
        assertEquals(ComputeBlendModule.wordCount(), spirv.length / 4);
        assertEquals(0, spirv.length % 4, "Byte count must be a multiple of four");
        assertTrue(validation.instructionCount() > 40,
                "Too few instructions (" + validation.instructionCount() + "); the module may be truncated");

        byte[] first = ComputeBlendModule.bytes();
        first[0] = 0;
        assertEquals(SpirvGen.MAGIC, le(ComputeBlendModule.bytes()).getInt(0),
                "Return a defensive copy of the module");
    }

    @Test
    @DisplayName("Header Is Correct")
    void headerIsCorrect() {
        ByteBuffer buf = le(ComputeBlendModule.bytes());

        assertEquals(SpirvGen.MAGIC, buf.getInt(0), "Expected SPIR-V magic 0x07230203");
        assertEquals(1, (buf.getInt(4) >>> 16) & 0xFF, "SPIR-V major version must be one");
        assertEquals(0, buf.getInt(16), "The reserved header field must be zero");
        assertTrue(ComputeBlendModule.DECLARED_BOUND > 0, "The ID bound must be positive");
    }

    @Test
    @DisplayName("Entry Point Contract")
    void entryPointContract() {
        List<Instruction> instructions = disassemble(ComputeBlendModule.bytes());

        Instruction entry = instructions.stream()
                .filter(i -> i.opcode() == OP_ENTRY_POINT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The module contains no OpEntryPoint"));

        assertEquals("main", entryPointName(instructions),
                "The entry point must be main, matching every caller's pName");
        assertEquals(EXECUTION_MODEL_GLCOMPUTE, entry.operands()[0],
                "Expected GLCompute execution model (5)");
    }

    @Test
    @DisplayName("Local Size Matches Java Constant")
    void localSizeMatchesJavaConstant() {
        int[] localSize = SpirvGen.localSize(ComputeBlendModule.bytes());

        assertNotNull(localSize, "The module must declare LocalSize");
        // Structural validity alone cannot verify agreement between the module and Java constants.
        assertEquals(ComputeBlendModule.LOCAL_SIZE, localSize[0],
                "LocalSize.x must match LOCAL_SIZE");
        assertEquals(ComputeBlendModule.LOCAL_SIZE, localSize[1],
                "LocalSize.y must match LOCAL_SIZE for the 8x8 workgroup");
        assertEquals(1, localSize[2], "LocalSize.z must be one");
    }

    @Test
    @DisplayName("Descriptor Bindings Match Java Constants")
    void descriptorBindingsMatchJavaConstants() {
        List<Instruction> instructions = disassemble(ComputeBlendModule.bytes());

        List<Integer> bindingNumbers = new ArrayList<>();
        for (int[] pair : decorations(instructions, DECORATION_BINDING)) {
            bindingNumbers.add(pair[1]);
        }
        bindingNumbers.sort(Integer::compareTo);

        assertTrue(bindingNumbers.contains(ComputeBlendModule.BINDING_INPUT_A),
                "Input A must use binding " + ComputeBlendModule.BINDING_INPUT_A
                        + "; actual bindings: " + bindingNumbers);
        assertTrue(bindingNumbers.contains(ComputeBlendModule.BINDING_INPUT_B),
                "Input B must use binding " + ComputeBlendModule.BINDING_INPUT_B
                        + "; actual bindings: " + bindingNumbers);
        assertTrue(bindingNumbers.contains(ComputeBlendModule.BINDING_OUTPUT),
                "Output must use binding " + ComputeBlendModule.BINDING_OUTPUT
                        + "; actual bindings: " + bindingNumbers);

        // DescriptorSet is 34; 3 is BufferBlock.
        List<Integer> sets = new ArrayList<>();
        for (int[] pair : decorations(instructions, DECORATION_DESCRIPTOR_SET)) {
            sets.add(pair[1]);
        }
        assertTrue(sets.contains(0), "Descriptors must use set zero: " + sets);
    }

    @Test
    @DisplayName("Push Constant Layout Matches Java Constants")
    void pushConstantLayoutMatchesJavaConstants() {
        List<Instruction> instructions = disassemble(ComputeBlendModule.bytes());

        // The push-constant structure must have the Block decoration.
        assertFalse(decorations(instructions, DECORATION_BLOCK).isEmpty(),
                "The push-constant structure must have the Block decoration");

        // Offset is 35; 6 is ArrayStride. Both the old handwritten module and its test once used the wrong enumerant.
        int[] memberOffsets = new int[4];
        java.util.Arrays.fill(memberOffsets, -1);
        for (Instruction instruction : instructions) {
            if (instruction.opcode() != OP_MEMBER_DECORATE) {
                continue;
            }
            int[] operands = instruction.operands();
            if (operands.length >= 4 && operands[2] == DECORATION_OFFSET) {
                int member = operands[1];
                if (member >= 0 && member < memberOffsets.length) {
                    memberOffsets[member] = operands[3];
                }
            }
        }

        assertEquals(ComputeBlendModule.PUSH_MIX_FACTOR_OFFSET, memberOffsets[0],
                "Blend factor (member zero) must have offset " + ComputeBlendModule.PUSH_MIX_FACTOR_OFFSET);
        // Four packed floats occupy 16 bytes, matching the Java PUSH_CONSTANT_BYTES allocation.
        assertEquals(ComputeBlendModule.PUSH_CONSTANT_BYTES, memberOffsets[3] + 4,
                "Push constants must occupy exactly " + ComputeBlendModule.PUSH_CONSTANT_BYTES
                        + " bytes; actual member offsets: " + java.util.Arrays.toString(memberOffsets));
    }

    @Test
    @DisplayName("Shader Actually Reads And Writes")
    void shaderActuallyReadsAndWrites() {
        Set<Integer> opcodes = new HashSet<>();
        int reads = 0;
        int writes = 0;
        for (Instruction instruction : disassemble(ComputeBlendModule.bytes())) {
            opcodes.add(instruction.opcode());
            if (instruction.opcode() == OP_IMAGE_FETCH || instruction.opcode() == OP_IMAGE_READ) {
                reads++;
            }
            if (instruction.opcode() == OP_IMAGE_WRITE) {
                writes++;
            }
        }

        // Do not require a specific instruction count: compilers may choose fetch/read instructions or optimize expressions. Both input images must still be read.
        assertTrue(reads >= 2, "Read both input images; actual read count: " + reads
                + ". Image instruction opcodes: " + opcodes);
        assertTrue(writes >= 1, "Write the output image at least once; actual count: " + writes + " times");
    }

    @Test
    @DisplayName("Rejects Corrupted Module")
    void rejectsCorruptedModule() {
        byte[] spirv = ComputeBlendModule.bytes();

        int checked = 0;
        for (Instruction instruction : disassemble(spirv)) {
            boolean textureOp = instruction.opcode() == OP_IMAGE_FETCH
                    || instruction.opcode() == OP_IMAGE_READ
                    || instruction.opcode() == OP_IMAGE_WRITE;
            if (!textureOp) {
                continue;
            }
            byte[] broken = ComputeBlendModule.bytes();
            // In little-endian words, the upper 16-bit word count occupies byte offsets +2 and +3.
            broken[instruction.offset() * 4 + 2] = 0;
            broken[instruction.offset() * 4 + 3] = 0;
            SpirvGen.Validation validation = SpirvGen.validate(broken);
            assertFalse(validation.valid(),
                    "Instruction @" + instruction.offset() + " must fail validation after zeroing its word count");
            assertTrue(validation.error().contains("字数为 0"), validation.error());
            checked++;
        }
        assertTrue(checked >= 3, "Expected at least three image instructions; actual: " + checked);
    }

    @Test
    @DisplayName("Constants Are Consistent")
    void constantsAreConsistent() {
        assertEquals(8, ComputeBlendModule.LOCAL_SIZE,
                "8x8 workgroup; keep this synchronized with blend.slang numthreads");
        assertEquals(0, ComputeBlendModule.BINDING_INPUT_A);
        assertEquals(1, ComputeBlendModule.BINDING_INPUT_B);
        assertEquals(2, ComputeBlendModule.BINDING_OUTPUT);
        assertEquals(0, ComputeBlendModule.PUSH_MIX_FACTOR_OFFSET);
        assertEquals(16, ComputeBlendModule.PUSH_CONSTANT_BYTES,
                "One float plus three padding floats");
        assertTrue(ComputeBlendModule.wordCount() > 0, "The module must not be empty");
        assertTrue(ComputeBlendModule.DECLARED_BOUND > 40,
                "The ID bound must cover every module ID");
    }
}
