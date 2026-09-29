package dev.luxloader.api.vulkan;

/**
 * Built-in frame blending compute shader. Fetches matching integer coordinates from two equally sized
 * inputs, writes mix(a,b,mixFactor) with alpha 1, and skips out-of-bounds invocations. Integer fetch
 * avoids extra sampler blur. This is a simple interpolation fallback; motion-aware generation requires
 * additional reprojection. <p>Compiled by slangc from src/main/slang/blend.slang using
 * :luxloader-api:compileShaders; blend.spv is checked in. Explicit vk::binding annotations keep shader
 * and Java bindings consistent. Do not hand-assemble SPIR-V: structural checks cannot validate
 * semantics and malformed modules can crash drivers. <p>Push constants occupy 16 bytes: mixFactor at 0
 * (0=A, 1=B), padding floats at 4, 8 and 12.
 */
public final class ComputeBlendModule {

    /** Compiled resource path relative to src/main/resources. */
    static final String RESOURCE = "/spirv/blend.spv";

    /** Shader source path for error messages. */
    static final String SOURCE = "luxloader-api/src/main/slang/blend.slang";

    /** Read-only compiled module loaded once at class initialization. */
    private static final byte[] MODULE = ModuleResources.load(RESOURCE, SOURCE);

    /** Workgroup side length: 8x8=64 invocations, matching numthreads(8,8,1). */
    public static final int LOCAL_SIZE = 8;

    /** Byte offset of the push constant blend factor. */
    public static final int PUSH_MIX_FACTOR_OFFSET = 0;

    /** Total push constant bytes. */
    public static final int PUSH_CONSTANT_BYTES = 16;

    /** Descriptor binding for input A. */
    public static final int BINDING_INPUT_A = 0;

    /** Descriptor binding for input B. */
    public static final int BINDING_INPUT_B = 1;

    /** Descriptor binding for the output storage image. */
    public static final int BINDING_OUTPUT = 2;

    /** ID bound read from the compiled header. Never hardcode it: compiler or source changes can alter it. */
    public static final int DECLARED_BOUND = ModuleResources.readBound(MODULE);

    private ComputeBlendModule() {
    }

    /** Returns a defensive copy of the SPIR-V binary. */
    public static byte[] bytes() {
        return MODULE.clone();
    }

    /** Word count. */
    public static int wordCount() {
        return MODULE.length / 4;
    }
}
