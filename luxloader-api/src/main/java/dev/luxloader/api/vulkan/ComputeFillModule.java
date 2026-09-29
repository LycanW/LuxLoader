package dev.luxloader.api.vulkan;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Built-in GPU fill test shader compiled from src/main/slang/fill.slang by
 * :luxloader-api:compileShaders. Checked-in fill.spv lets normal builds run without slangc. Each
 * invocation writes four uint values equal to FILL_VALUE, with numthreads(64,1,1), exercising
 * descriptor binding, pipeline creation, dispatch and readback. <p>Earlier hand-built modules had
 * incorrect OpEntryPoint/OpConstantComposite word counts and mismatched LocalSize. Structural
 * self-consistency cannot catch all semantic mistakes, and malformed modules may crash the JVM through
 * the driver. Keep generation in the shader compiler.
 */
public final class ComputeFillModule {

    /** Compiled resource path relative to src/main/resources. */
    static final String RESOURCE = "/spirv/fill.spv";

    /** Shader source path for error messages. */
    static final String SOURCE = "luxloader-api/src/main/slang/fill.slang";

    /** Read-only compiled module loaded once at class initialization. */
    private static final byte[] MODULE = load();

    /** Workgroup size matching numthreads(64,1,1). */
    public static final int LOCAL_SIZE_X = 64;

    /** Elements written per invocation. */
    public static final int ELEMENTS_PER_INVOCATION = 4;

    /** Elements covered by one dispatched workgroup. */
    public static final int TOTAL_ELEMENTS = LOCAL_SIZE_X * ELEMENTS_PER_INVOCATION;

    /** Expected readback value: decimal 1234567890. */
    public static final int FILL_VALUE = 1234567890;

    /** Descriptor binding matching vk::binding(1,0). */
    public static final int BINDING = 1;

    /** ID bound read from the compiled header; never hardcode a compiler-dependent value. */
    public static final int DECLARED_BOUND = ModuleResources.readBound(MODULE);

    /** Module word count read from the artifact. */
    public static final int WORD_COUNT = MODULE.length / 4;

    private ComputeFillModule() {
    }

    /** Returns a defensive copy of the SPIR-V binary. */
    public static byte[] bytes() {
        return MODULE.clone();
    }

    /** Module word count for validation. */
    public static int wordCount() {
        return MODULE.length / 4;
    }

    /** Read-only word access for diagnostics and tests. */
    public static int word(int index) {
        return ByteBuffer.wrap(MODULE).order(ByteOrder.LITTLE_ENDIAN).getInt(index * 4);
    }

    private static byte[] load() {
        return ModuleResources.load(RESOURCE, SOURCE);
    }
}
