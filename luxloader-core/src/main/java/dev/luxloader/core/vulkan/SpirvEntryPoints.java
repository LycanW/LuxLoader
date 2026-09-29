package dev.luxloader.core.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Driver-independent rejection of absent entry points before native pipeline compilation. */
final class SpirvEntryPoints {
    private SpirvEntryPoints() { }
    static void require(byte[] spirv, String entry, int executionModel) {
        if (spirv == null || spirv.length < 20 || spirv.length % 4 != 0)
            throw new IllegalArgumentException("Invalid SPIR-V module length");
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
        if (words.getInt(0) != 0x07230203) throw new IllegalArgumentException("Invalid SPIR-V magic");
        var available = new ArrayList<String>();
        for (int offset = 20; offset < spirv.length;) {
            int instruction = words.getInt(offset), count = instruction >>> 16, opcode = instruction & 0xffff;
            if (count == 0 || offset + (long)count * 4 > spirv.length)
                throw new IllegalArgumentException("Invalid SPIR-V instruction at byte " + offset);
            if (opcode == 15 && count >= 4) {
                int start = offset + 12, end = start;
                while (end < offset + count * 4 && spirv[end] != 0) end++;
                if (end == offset + count * 4) throw new IllegalArgumentException("Unterminated SPIR-V entry point");
                String name = new String(spirv, start, end - start, StandardCharsets.UTF_8);
                int model = words.getInt(offset + 4);
                available.add(name + " (model " + model + ")");
                if (name.equals(entry) && model == executionModel) return;
            }
            offset += count * 4;
        }
        throw new IllegalArgumentException("SPIR-V has no entry '" + entry + "' for execution model "
                + executionModel + "; available: " + available);
    }
}
