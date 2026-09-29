// Frame blending shader for equally sized display-resolution images. Integer-coordinate fetch avoids sampler blur; bounds checks protect partial 8x8 groups. Keep ComputeBlendModule constants aligned: local size 8x8x1; bindings A=0, B=1, output=2; mixFactor offset 0 in a 16-byte push block. Compile with :luxloader-api:compileShaders or slangc blend.slang -target spirv -profile spirv_1_5 -entry main -stage compute -o blend.spv. Use explicit vk::binding annotations: D3D register declarations do not fix Vulkan bindings and produce E39029 warnings.

[[vk::binding(0, 0)]]
Texture2D<float4> inputA;

[[vk::binding(1, 0)]]
Texture2D<float4> inputB;

[[vk::binding(2, 0)]]
// Declare the storage image format explicitly. Unformatted float4 storage emits StorageImageRead/WriteWithoutFormat capabilities requiring device features that cannot be added to the host-owned device. This previously failed shader creation with VUID 08740 / VK_ERROR_INVALID_SPIRV. Match the Java view format R16G16B16A16_SFLOAT used by FrameGenerationPass.frameDesc. Earlier configuration-loading failures hid this path by preventing initialization.
[[vk::image_format("rgba16f")]]
RWTexture2D<float4> output;

struct PushData
{
    // Offset 0: zero selects A, one selects B.
    float mixFactor;
    // Three padding floats complete the 16-byte block; push constant alignment is four bytes.
    float padding0;
    float padding1;
    float padding2;
};

[[vk::push_constant]] ConstantBuffer<PushData> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID)
{
    uint2 coord = tid.xy;

    uint width;
    uint height;
    output.GetDimensions(width, height);
    if (coord.x >= width || coord.y >= height)
    {
        return;
    }

    float3 a = inputA[coord].rgb;
    float3 b = inputB[coord].rgb;
    output[coord] = float4(lerp(a, b, push.mixFactor), 1.0);
}
