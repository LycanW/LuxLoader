// GPU fill test shader: each invocation writes four FILL_VALUE uints to verify descriptors, pipeline creation, dispatch and readback. Match ComputeFillModule: local size 64x1x1, binding 1, value 1234567890 (0x499602D2). Compile with :luxloader-api:compileShaders or slangc fill.slang -target spirv -profile spirv_1_5 -entry main -stage compute -o fill.spv. Explicit vk::binding is required; D3D register(uN) alone leaves Vulkan binding selection to the compiler and produces warning E39029.

[[vk::binding(1, 0)]]
RWStructuredBuffer<uint> data;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID)
{
    uint idx = tid.x * 4u;
    data[idx + 0u] = 0x499602D2u;
    data[idx + 1u] = 0x499602D2u;
    data[idx + 2u] = 0x499602D2u;
    data[idx + 3u] = 0x499602D2u;
}
