/*
 * Reference C ABI template for plugin native bridges. Vendor static libraries/C++ APIs need an extern
 * "C" shim; even C SDKs may benefit from native structure/callback handling. Export POD arguments and
 * opaque uint64 handles, pass structures by pointer, keep SDK callbacks in native code, return int
 * status with lux_last_error(), and never propagate C++ exceptions across the boundary. Avoid runtime
 * conflicts with JDK-bundled MSVC DLLs by using a compatible runtime or static /MT linkage. NGX
 * initialization can use roughly 1 MB of stack; call it from a Java-created thread with a larger
 * explicit stack rather than relying on launcher -Xss settings. This header documents an integration
 * pattern rather than a built-in vendor implementation.
 */

#ifndef LUXLOADER_BRIDGE_H
#define LUXLOADER_BRIDGE_H

#include <stdint.h>
#include <stddef.h>

#if defined(_WIN32)
#  define LUX_EXPORT __declspec(dllexport)
#  define LUX_CALL   __cdecl
#else
#  define LUX_EXPORT __attribute__((visibility("default")))
#  define LUX_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/* Common conventions. */
/* ------------------------------------------------------------------ */

/* Status codes: zero succeeds, negative values belong to the bridge, positive values to vendor SDKs. */
typedef enum LuxResult {
    LUX_OK                  = 0,
    LUX_ERR_NOT_INITIALIZED = -1,
    LUX_ERR_INVALID_ARG     = -2,
    LUX_ERR_LIBRARY_MISSING = -3,
    LUX_ERR_SYMBOL_MISSING  = -4,
    LUX_ERR_UNSUPPORTED     = -5,
    LUX_ERR_VENDOR_FAILED   = -6,
    LUX_ERR_OUT_OF_MEMORY   = -7
} LuxResult;

/* Opaque handle stored/passed as uint64 in Java; never interpret its contents. */
typedef uint64_t LuxHandle;

/*
 * Returns the last error as const char*. After a nonzero status, Java reads the address through
 * MemorySegment. The thread-local buffer remains valid only until the next library call.
 */
LUX_EXPORT const char* LUX_CALL lux_last_error(void);

/* Library version, e.g. 0x00010000 for 1.0.0, used for Java compatibility checks. */
LUX_EXPORT uint32_t LUX_CALL lux_bridge_version(void);

/*
 * Probes availability without initialization. Capability keys: dlss.sr (upscale), dlss.fg (frame
 * generation), dlss.rr (ray reconstruction), dlss.nr (neural rendering), xess.sr, xess.fg (D3D12 only
 * in this contract), fsr.upscale and fsr.fg. Nonnegative levels: 0 unsupported, 1 fallback, 2 partial,
 * 3 native SDK, 4 full. Negative values mean query failure; -3 means a missing library.
 */
LUX_EXPORT int32_t LUX_CALL lux_probe_capability(const char* capability);

/* ------------------------------------------------------------------ */
/* DLSS / NGX                                                          */
/* ------------------------------------------------------------------ */

/*
 * Initializes NGX with raw Vulkan handles, application/project IDs and engine version. The caller must
 * provide sufficient stack, preferably a dedicated Java thread with at least 4 MB. On LUX_OK, out_ngx
 * receives the context handle.
 */
LUX_EXPORT int32_t LUX_CALL lux_ngx_init(
        uint64_t vk_instance,
        uint64_t vk_physical_device,
        uint64_t vk_device,
        const char* app_id,
        const char* project_id,
        uint64_t engine_version,
        LuxHandle* out_ngx);

LUX_EXPORT int32_t LUX_CALL lux_ngx_shutdown(LuxHandle ngx);

/*
 * Creates a dlss.sr/fg/rr/nr feature with render/display dimensions and LuxQuality. The implementation
 * queries capabilities, sets parameters and invokes NVSDK_NGX_VULKAN_CreateFeature1; out_feature
 * receives the handle.
 */
LUX_EXPORT int32_t LUX_CALL lux_ngx_create_feature(
        LuxHandle ngx,
        const char* feature,
        uint32_t render_w, uint32_t render_h,
        uint32_t display_w, uint32_t display_h,
        int32_t quality,
        LuxHandle* out_feature);

LUX_EXPORT int32_t LUX_CALL lux_ngx_release_feature(LuxHandle feature);

/*
 * Per-frame parameters use row-major, unjittered matrices with jitter passed separately. Minecraft
 * normally uses reversed Z (near=1). Motion vectors are current-to-previous screen pixel offsets;
 * mv_scale_x/y converts other directions or UV units.
 */
typedef struct LuxFrameParams {
    /* Row-major 4x4 camera matrices, 16 floats each. */
    float camera_view_to_clip[16];
    float clip_to_camera_view[16];
    float clip_to_prev_clip[16];
    float prev_clip_to_clip[16];

    /* Subpixel jitter in render-resolution pixels, excluded from matrices. */
    float jitter_x;
    float jitter_y;

    /* Motion vector scaling from renderer conventions to SDK conventions. */
    float mv_scale_x;
    float mv_scale_y;

    /* Depth convention. */
    int32_t depth_inverted;       /* 1 enables reversed Z, Minecraft's default. */
    int32_t depth_infinite_far;
    float   camera_near;
    float   camera_far;

    float fov_y_radians;
    float aspect_ratio;

    /* Exposure; zero when using an exposure texture. */
    float exposure;

    /* Set to 1 after scene/size changes or history invalidation to avoid ghosting. */
    int32_t reset;

    /* Frame index, monotonically increasing for frame generation. */
    uint64_t frame_index;
} LuxFrameParams;

/* Input image reference with native VkImage/VkImageView handles. */
typedef struct LuxImageRef {
    uint64_t image;
    uint64_t view;
    uint32_t width;
    uint32_t height;
    uint32_t format;   /* VkFormat value. */
} LuxImageRef;

/*
 * Records evaluation into the supplied VkCommandBuffer without submitting or blocking. The
 * implementation maps semantic resources to SDK tags. Never wait for queue idle or perform synchronous
 * readback here.
 */
LUX_EXPORT int32_t LUX_CALL lux_ngx_evaluate(
        LuxHandle feature,
        uint64_t command_buffer,
        const LuxFrameParams* params,
        const LuxImageRef* color,
        const LuxImageRef* depth,
        const LuxImageRef* motion_vectors,
        const LuxImageRef* exposure,
        const LuxImageRef* output);

/* ------------------------------------------------------------------ */
/* XeSS                                                                */
/* ------------------------------------------------------------------ */

/*
 * Creates a XeSS context. Required extensions/features from lux_xess_required_* must already be
 * enabled before instance/device creation; this call cannot add them. Returns the handle through
 * out_context.
 */
LUX_EXPORT int32_t LUX_CALL lux_xess_create_context(
        uint64_t vk_instance,
        uint64_t vk_physical_device,
        uint64_t vk_device,
        LuxHandle* out_context);

LUX_EXPORT int32_t LUX_CALL lux_xess_destroy_context(LuxHandle context);

/*
 * Queries required names. kind: 0 instance extensions, 1 device extensions, 2 device features.
 * out_count reports the size; null out_names performs a count-only query, otherwise capacity limits
 * the caller's buffer. Query, allocate, fill, then declare each VulkanDispatch requirement.
 */
LUX_EXPORT int32_t LUX_CALL lux_xess_required(
        int32_t kind,
        char** out_names,
        uint32_t capacity,
        uint32_t* out_count);

/*
 * Initialization and per-frame execution. Nonzero temporary buffer/texture heaps are caller-owned;
 * zero requests SDK allocation where supported by the SDK version.
 */
LUX_EXPORT int32_t LUX_CALL lux_xess_init(
        LuxHandle context,
        uint32_t render_w, uint32_t render_h,
        uint32_t display_w, uint32_t display_h,
        int32_t quality,
        uint64_t temp_buffer_heap,
        uint64_t temp_texture_heap);

LUX_EXPORT int32_t LUX_CALL lux_xess_execute(
        LuxHandle context,
        uint64_t command_buffer,
        uint64_t color_image,
        uint64_t depth_image,
        uint64_t motion_image,
        uint64_t output_image,
        const LuxFrameParams* params);

/* Sets XeSS motion-vector scaling and jitter conventions before lux_xess_init as required by the SDK. */
LUX_EXPORT int32_t LUX_CALL lux_xess_set_velocity_scale(float x, float y);

/* ------------------------------------------------------------------ */
/* Shared quality levels. */
/* ------------------------------------------------------------------ */

typedef enum LuxQuality {
    LUX_QUALITY_NATIVE            = 0,  /* Native resolution with temporal antialiasing. */
    LUX_QUALITY_ULTRA_QUALITY     = 1,
    LUX_QUALITY_QUALITY           = 2,
    LUX_QUALITY_BALANCED          = 3,
    LUX_QUALITY_PERFORMANCE       = 4,
    LUX_QUALITY_ULTRA_PERFORMANCE = 5
} LuxQuality;

/* Maps LuxQuality to the vendor's native quality enum value in one place. */
LUX_EXPORT int32_t LUX_CALL lux_map_quality(int32_t vendor, LuxQuality quality);

/* ------------------------------------------------------------------ */
/* License notice. */
/* ------------------------------------------------------------------ */

/*
 * Distribution policy for this bridge template: do not bundle NVIDIA driver DLLs in the mod. Probe
 * system locations and return LUX_ERR_LIBRARY_MISSING with actionable driver-update guidance when
 * absent. XeSS binary redistribution must retain applicable license/copyright notices and respect its
 * license restrictions; verify the exact SDK terms when packaging.
 */

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* LUXLOADER_BRIDGE_H */
