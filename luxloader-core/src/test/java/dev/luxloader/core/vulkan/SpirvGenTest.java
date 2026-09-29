package dev.luxloader.core.vulkan;

import dev.luxloader.api.vulkan.ComputeFillModule;
import dev.luxloader.api.vulkan.SpirvGen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests built-in SPIR-V structure and interfaces: main compute entry point, workgroup size, bindings
 * and fill values. Modules are compiled from Slang; avoid compiler-specific instruction sequences.
 * Structural validity alone cannot catch a mismatch between shader LocalSize and Java dispatch
 * constants.
 */
class SpirvGenTest {

    /** The module header occupies five words; instructions start at word five. */
    private static final int FIRST_INSTRUCTION_WORD = 5;

    // Named SPIR-V enumerants used by assertions.

    private static final int OP_ENTRY_POINT = 15;
    private static final int OP_EXECUTION_MODE = 16;
    private static final int OP_CONSTANT = 43;
    private static final int OP_DECORATE = 71;

    private static final int EXECUTION_MODEL_GLCOMPUTE = 5;
    private static final int EXECUTION_MODE_LOCAL_SIZE = 17;
    private static final int DECORATION_BINDING = 33;

    private static ByteBuffer littleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Find an instruction's word offset by walking variable-length instructions from the start. */
    private static int instructionWordOffset(byte[] spirv, int targetIndex) {
        ByteBuffer buf = littleEndian(spirv);
        int words = spirv.length / 4;
        int offset = FIRST_INSTRUCTION_WORD;
        for (int i = 0; i < targetIndex; i++) {
            int count = (buf.getInt(offset * 4) >>> 16) & 0xFFFF;
            if (count == 0) {
                fail("Compilation " + i + " instruction has zero word count; traversal cannot continue");
            }
            offset += count;
        }
        return offset;
    }

    /** Instruction opcode and word operands. */
    private record Instruction(int opcode, int[] operands) {
    }

    /** Decode every instruction in the module. */
    private static List<Instruction> disassemble(byte[] spirv) {
        ByteBuffer buf = littleEndian(spirv);
        int words = spirv.length / 4;
        List<Instruction> out = new ArrayList<>();
        int offset = FIRST_INSTRUCTION_WORD;
        while (offset < words) {
            int header = buf.getInt(offset * 4);
            int count = (header >>> 16) & 0xFFFF;
            if (count == 0) {
                fail("Zero-length instruction at word " + offset);
            }
            int[] operands = new int[count - 1];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = buf.getInt((offset + 1 + i) * 4);
            }
            out.add(new Instruction(header & 0xFFFF, operands));
            offset += count;
        }
        return out;
    }

    /** Decode the OpEntryPoint name. */
    private static String entryPointName(byte[] spirv) {
        for (Instruction instruction : disassemble(spirv)) {
            if (instruction.opcode() != OP_ENTRY_POINT) {
                continue;
            }
            // [model][function][name...][interface...]
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < instruction.operands().length; i++) {
                int word = instruction.operands()[i];
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

    /** Decode all Binding decoration values. */
    private static List<Integer> bindings(byte[] spirv) {
        List<Integer> out = new ArrayList<>();
        for (Instruction instruction : disassemble(spirv)) {
            if (instruction.opcode() == OP_DECORATE && instruction.operands().length >= 3
                    && instruction.operands()[1] == DECORATION_BINDING) {
                out.add(instruction.operands()[2]);
            }
        }
        out.sort(Integer::compareTo);
        return out;
    }

    @Test
    @DisplayName("Module Is Present")
    void moduleIsPresent() {
        byte[] spirv = ComputeFillModule.bytes();
        assertNotNull(spirv);
        assertTrue(spirv.length > 0, "The module must not be empty");
        assertEquals(0, spirv.length % 4, "Byte count must be a multiple of four");
        assertEquals(ComputeFillModule.WORD_COUNT, ComputeFillModule.wordCount(),
                "WORD_COUNT must match wordCount()");
        assertEquals(ComputeFillModule.WORD_COUNT * 4, spirv.length,
                "Byte count must equal four times word count");

        // Mutating the returned value must not affect internal state.
        spirv[0] = 0;
        assertEquals(SpirvGen.MAGIC, littleEndian(ComputeFillModule.bytes()).getInt(0),
                "Return a defensive copy immune to external mutation");
    }

    @Test
    @DisplayName("Header Is Correct")
    void headerIsCorrect() {
        ByteBuffer buf = littleEndian(ComputeFillModule.bytes());

        assertEquals(SpirvGen.MAGIC, buf.getInt(0), "Expected SPIR-V magic 0x07230203");
        int version = buf.getInt(4);
        assertEquals(1, (version >>> 16) & 0xFF, "SPIR-V major version must be one");
        int minor = (version >>> 8) & 0xFF;
        assertTrue(minor >= 0 && minor <= 6,
                "Expected SPIR-V version 1.0 through 1.6; actual 1." + minor);
        assertEquals(0, buf.getInt(16), "The reserved header field must be zero");

        // DECLARED_BOUND comes from the generated header, also checking the header read offset.
        assertEquals(ComputeFillModule.DECLARED_BOUND, buf.getInt(12), "ID bound");
        assertTrue(ComputeFillModule.DECLARED_BOUND > 0, "The ID bound must be positive");
    }

    @Test
    @DisplayName("Module Is Structurally Valid")
    void moduleIsStructurallyValid() {
        byte[] spirv = ComputeFillModule.bytes();
        SpirvGen.Validation result = SpirvGen.validate(spirv);

        assertTrue(result.valid(), "Structural validation failed: " + result.error() + "\n" + SpirvGen.describe(spirv));
        assertEquals(ComputeFillModule.DECLARED_BOUND, result.declaredBound());
        assertTrue(result.instructionCount() > 30,
                "Too few instructions (" + result.instructionCount() + "); the module may be truncated");
    }

    @Test
    @DisplayName("Entry Point Contract")
    void entryPointContract() {
        byte[] spirv = ComputeFillModule.bytes();
        List<Instruction> instructions = disassemble(spirv);

        Instruction entry = instructions.stream()
                .filter(i -> i.opcode() == OP_ENTRY_POINT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("The module contains no OpEntryPoint"));

        // Callers use main as pName, so the entry-point name must match.
        assertEquals("main", entryPointName(spirv),
                "The entry point must be main, matching every caller's pName");
        assertEquals(EXECUTION_MODEL_GLCOMPUTE, entry.operands()[0],
                "Expected GLCompute execution model (5)");
    }

    @Test
    @DisplayName("Local Size Matches Java Constant")
    void localSizeMatchesJavaConstant() {
        int[] localSize = SpirvGen.localSize(ComputeFillModule.bytes());

        assertNotNull(localSize, "The module must declare LocalSize for correct dispatch sizing");
        // A historical module used LocalSize (11,1,1) while Java used 64; structural validation alone missed the mismatch.
        assertEquals(ComputeFillModule.LOCAL_SIZE_X, localSize[0],
                "LocalSize.x differs from LOCAL_SIZE_X: shader threads per group = "
                        + localSize[0] + "; host assumes " + ComputeFillModule.LOCAL_SIZE_X
                        + " when calculating coverage");
        assertEquals(1, localSize[1], "LocalSize.y must be one");
        assertEquals(1, localSize[2], "LocalSize.z must be one");
    }

    @Test
    @DisplayName("Binding Matches Java Constant")
    void bindingMatchesJavaConstant() {
        List<Integer> bindings = bindings(ComputeFillModule.bytes());

        assertTrue(bindings.contains(ComputeFillModule.BINDING),
                "The module must use descriptor binding " + ComputeFillModule.BINDING
                        + "; actual bindings: " + bindings);
    }

    @Test
    @DisplayName("Fill Value Is Embedded")
    void fillValueIsEmbedded() {
        boolean found = disassemble(ComputeFillModule.bytes()).stream()
                .filter(i -> i.opcode() == OP_CONSTANT && i.operands().length >= 3)
                .anyMatch(i -> i.operands()[2] == ComputeFillModule.FILL_VALUE);

        assertTrue(found, "The module must contain a constant with value " + ComputeFillModule.FILL_VALUE
                + "（0x" + Integer.toHexString(ComputeFillModule.FILL_VALUE)
                + ") for meaningful readback validation");
    }

    @Test
    @DisplayName("Rejects Malformed Modules")
    void rejectsMalformedModules() {
        assertFalse(SpirvGen.validate(null).valid(), "Reject null");
        assertFalse(SpirvGen.validate(new byte[0]).valid(), "Reject empty arrays");
        assertFalse(SpirvGen.validate(new byte[7]).valid(), "Reject lengths not divisible by four");

        byte[] tooShort = new byte[16];
        assertFalse(SpirvGen.validate(tooShort).valid(), "Reject incomplete headers");

        byte[] wrongMagic = ComputeFillModule.bytes();
        wrongMagic[0] = 0x00;
        SpirvGen.Validation magic = SpirvGen.validate(wrongMagic);
        assertFalse(magic.valid());
        assertTrue(magic.error().contains("魔数"), magic.error());

        // Zero a real instruction's word count without hard-coding an instruction index.
        for (int instructionIndex : new int[] {2, 3, 5, 10, 20}) {
            byte[] zeroCount = ComputeFillModule.bytes();
            int offset = instructionWordOffset(zeroCount, instructionIndex);
            // In little-endian words, the upper 16-bit word count occupies byte offsets +2 and +3.
            zeroCount[offset * 4 + 2] = 0;
            zeroCount[offset * 4 + 3] = 0;
            SpirvGen.Validation zero = SpirvGen.validate(zeroCount);
            assertFalse(zero.valid(),
                    "Compilation " + instructionIndex + " instruction at word " + offset + ") must fail validation after zeroing its word count");
            assertTrue(zero.error().contains("字数为 0"), zero.error());
        }

        // Make an instruction extend beyond the module length.
        byte[] overflow = ComputeFillModule.bytes();
        int overflowOffset = instructionWordOffset(overflow, 3);
        overflow[overflowOffset * 4 + 2] = (byte) 0xFF;
        overflow[overflowOffset * 4 + 3] = (byte) 0x7F;
        assertFalse(SpirvGen.validate(overflow).valid(), "Detect word counts extending beyond the module");

        // Set the ID bound to zero.
        byte[] badBound = ComputeFillModule.bytes();
        badBound[12] = 0;
        badBound[13] = 0;
        badBound[14] = 0;
        badBound[15] = 0;
        assertFalse(SpirvGen.validate(badBound).valid(), "Reject a zero ID bound");

        // Set an unsupported major version.
        byte[] badVersion = ComputeFillModule.bytes();
        badVersion[6] = 0x02;
        assertFalse(SpirvGen.validate(badVersion).valid(), "Reject unsupported versions");
    }

    @Test
    @DisplayName("Local Size Rejects Malformed Input")
    void localSizeRejectsMalformedInput() {
        assertNull(SpirvGen.localSize(null), "Null input must return null");
        assertNull(SpirvGen.localSize(new byte[0]), "Empty input must return null");
        assertNull(SpirvGen.localSize(new byte[7]), "Invalid lengths must return null");

        byte[] zeroCount = ComputeFillModule.bytes();
        int offset = instructionWordOffset(zeroCount, 3);
        zeroCount[offset * 4 + 2] = 0;
        zeroCount[offset * 4 + 3] = 0;
        assertNull(SpirvGen.localSize(zeroCount), "Do not decode results from structurally corrupt modules");
    }

    @Test
    @DisplayName("Describe Points At The Problem")
    void describePointsAtTheProblem() {
        String dump = SpirvGen.describe(ComputeFillModule.bytes());

        assertTrue(dump.contains("magic=0x07230203"), dump);
        assertTrue(dump.contains("bound=" + ComputeFillModule.DECLARED_BOUND), dump);
        assertTrue(dump.contains("#0"), dump);

        byte[] broken = ComputeFillModule.bytes();
        int offset = instructionWordOffset(broken, 3);
        broken[offset * 4 + 2] = 0;
        broken[offset * 4 + 3] = 0;
        String brokenDump = SpirvGen.describe(broken);
        assertTrue(brokenDump.contains("损坏"), brokenDump);
    }

    @Test
    @DisplayName("Byte Buffer Wrapping")
    void byteBufferWrapping() {
        byte[] spirv = ComputeFillModule.bytes();
        ByteBuffer buffer = SpirvGen.asBuffer(spirv);
        assertEquals(spirv.length, buffer.remaining());
        assertEquals(SpirvGen.MAGIC, buffer.order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        assertTrue(SpirvGen.looksValid(spirv));
    }

    @Test
    @DisplayName("Shader Constants Are Consistent")
    void shaderConstantsAreConsistent() {
        assertEquals(64, ComputeFillModule.LOCAL_SIZE_X);
        assertEquals(4, ComputeFillModule.ELEMENTS_PER_INVOCATION);
        assertEquals(256, ComputeFillModule.TOTAL_ELEMENTS,
                "64 threads with four elements each must produce 256 uints");
        assertNotEquals(0, ComputeFillModule.FILL_VALUE, "Use a nonzero fill value to distinguish output from cleared memory");
        assertTrue(ComputeFillModule.BINDING >= 0, "Binding numbers must be non-negative");
    }
}
