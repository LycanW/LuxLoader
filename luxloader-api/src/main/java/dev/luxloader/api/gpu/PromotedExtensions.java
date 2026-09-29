package dev.luxloader.api.gpu;

import java.util.Map;
import java.util.Set;

/**
 * Extensions promoted to Vulkan core. Capability checks accept either driver advertisement or
 * promotion to a core version supported by the device. Drivers need not advertise promoted extensions.
 * Use exact registry names: {@code VK_KHR_get_memory_requirements2} and {@code
 * VK_KHR_get_physical_device_properties2} have no underscore before the 2. <p>Generated from {@code
 * <VK_SDK>/share/vulkan/registry/vk.xml}, selecting extensions with {@code
 * promotedto="VK_VERSION_x_y"}. SDK 1.4.357.0 supplied 86 entries (81 device, 5 instance). {@code
 * PromotedExtensionsTest} recomputes and compares every entry when the local registry is available,
 * and skips otherwise. Promotion to another extension does not imply core availability and is
 * excluded.
 */
public final class PromotedExtensions {

    private PromotedExtensions() {
    }

    /**
     * Extension name to promoted core version, encoded as major << 22 | minor << 12 like
     * GpuCapabilities.apiVersion().
     */
    private static final Map<String, Integer> DEVICE = Map.ofEntries(
            // Vulkan 1.1: 18 extensions.
            e(1, 1, "VK_KHR_16bit_storage"),
            e(1, 1, "VK_KHR_bind_memory2"),
            e(1, 1, "VK_KHR_dedicated_allocation"),
            e(1, 1, "VK_KHR_descriptor_update_template"),
            e(1, 1, "VK_KHR_device_group"),
            e(1, 1, "VK_KHR_external_fence"),
            e(1, 1, "VK_KHR_external_memory"),
            e(1, 1, "VK_KHR_external_semaphore"),
            e(1, 1, "VK_KHR_get_memory_requirements2"),
            e(1, 1, "VK_KHR_maintenance1"),
            e(1, 1, "VK_KHR_maintenance2"),
            e(1, 1, "VK_KHR_maintenance3"),
            e(1, 1, "VK_KHR_multiview"),
            e(1, 1, "VK_KHR_relaxed_block_layout"),
            e(1, 1, "VK_KHR_sampler_ycbcr_conversion"),
            e(1, 1, "VK_KHR_shader_draw_parameters"),
            e(1, 1, "VK_KHR_storage_buffer_storage_class"),
            e(1, 1, "VK_KHR_variable_pointers"),
            // Vulkan 1.2: 24 extensions.
            e(1, 2, "VK_EXT_descriptor_indexing"),
            e(1, 2, "VK_EXT_host_query_reset"),
            e(1, 2, "VK_EXT_sampler_filter_minmax"),
            e(1, 2, "VK_EXT_scalar_block_layout"),
            e(1, 2, "VK_EXT_separate_stencil_usage"),
            e(1, 2, "VK_EXT_shader_viewport_index_layer"),
            e(1, 2, "VK_KHR_8bit_storage"),
            e(1, 2, "VK_KHR_buffer_device_address"),
            e(1, 2, "VK_KHR_create_renderpass2"),
            e(1, 2, "VK_KHR_depth_stencil_resolve"),
            e(1, 2, "VK_KHR_draw_indirect_count"),
            e(1, 2, "VK_KHR_driver_properties"),
            e(1, 2, "VK_KHR_image_format_list"),
            e(1, 2, "VK_KHR_imageless_framebuffer"),
            e(1, 2, "VK_KHR_sampler_mirror_clamp_to_edge"),
            e(1, 2, "VK_KHR_separate_depth_stencil_layouts"),
            e(1, 2, "VK_KHR_shader_atomic_int64"),
            e(1, 2, "VK_KHR_shader_float_controls"),
            e(1, 2, "VK_KHR_shader_float16_int8"),
            e(1, 2, "VK_KHR_shader_subgroup_extended_types"),
            e(1, 2, "VK_KHR_spirv_1_4"),
            e(1, 2, "VK_KHR_timeline_semaphore"),
            e(1, 2, "VK_KHR_uniform_buffer_standard_layout"),
            e(1, 2, "VK_KHR_vulkan_memory_model"),
            // Vulkan 1.3: 23 extensions.
            e(1, 3, "VK_EXT_4444_formats"),
            e(1, 3, "VK_EXT_extended_dynamic_state"),
            e(1, 3, "VK_EXT_extended_dynamic_state2"),
            e(1, 3, "VK_EXT_image_robustness"),
            e(1, 3, "VK_EXT_inline_uniform_block"),
            e(1, 3, "VK_EXT_pipeline_creation_cache_control"),
            e(1, 3, "VK_EXT_pipeline_creation_feedback"),
            e(1, 3, "VK_EXT_private_data"),
            e(1, 3, "VK_EXT_shader_demote_to_helper_invocation"),
            e(1, 3, "VK_EXT_subgroup_size_control"),
            e(1, 3, "VK_EXT_texel_buffer_alignment"),
            e(1, 3, "VK_EXT_texture_compression_astc_hdr"),
            e(1, 3, "VK_EXT_tooling_info"),
            e(1, 3, "VK_EXT_ycbcr_2plane_444_formats"),
            e(1, 3, "VK_KHR_copy_commands2"),
            e(1, 3, "VK_KHR_dynamic_rendering"),
            e(1, 3, "VK_KHR_format_feature_flags2"),
            e(1, 3, "VK_KHR_maintenance4"),
            e(1, 3, "VK_KHR_shader_integer_dot_product"),
            e(1, 3, "VK_KHR_shader_non_semantic_info"),
            e(1, 3, "VK_KHR_shader_terminate_invocation"),
            e(1, 3, "VK_KHR_synchronization2"),
            e(1, 3, "VK_KHR_zero_initialize_workgroup_memory"),
            // Vulkan 1.4: 16 extensions.
            e(1, 4, "VK_EXT_host_image_copy"),
            e(1, 4, "VK_EXT_pipeline_protected_access"),
            e(1, 4, "VK_EXT_pipeline_robustness"),
            e(1, 4, "VK_KHR_dynamic_rendering_local_read"),
            e(1, 4, "VK_KHR_global_priority"),
            e(1, 4, "VK_KHR_index_type_uint8"),
            e(1, 4, "VK_KHR_line_rasterization"),
            e(1, 4, "VK_KHR_load_store_op_none"),
            e(1, 4, "VK_KHR_maintenance5"),
            e(1, 4, "VK_KHR_maintenance6"),
            e(1, 4, "VK_KHR_map_memory2"),
            e(1, 4, "VK_KHR_push_descriptor"),
            e(1, 4, "VK_KHR_shader_expect_assume"),
            e(1, 4, "VK_KHR_shader_float_controls2"),
            e(1, 4, "VK_KHR_shader_subgroup_rotate"),
            e(1, 4, "VK_KHR_vertex_attribute_divisor"));

    /** Five instance extensions promoted to Vulkan 1.1 core. */
    private static final Map<String, Integer> INSTANCE = Map.ofEntries(
            e(1, 1, "VK_KHR_device_group_creation"),
            e(1, 1, "VK_KHR_external_fence_capabilities"),
            e(1, 1, "VK_KHR_external_memory_capabilities"),
            e(1, 1, "VK_KHR_external_semaphore_capabilities"),
            e(1, 1, "VK_KHR_get_physical_device_properties2"));

    private static Map.Entry<String, Integer> e(int major, int minor, String name) {
        return Map.entry(name, (major << 22) | (minor << 12));
    }

    /**
     * Whether promotion to core makes this device extension available.
     * @param extension extension name
     * @param apiVersion Vulkan version encoded as {@code major<<22 | minor<<12 | patch}
     */
    public static boolean deviceAvailableAtCore(String extension, int apiVersion) {
        Integer promoted = DEVICE.get(extension);
        return promoted != null && apiVersion >= promoted;
    }

    /** Whether core promotion makes an instance extension available. */
    public static boolean instanceAvailableAtCore(String extension, int apiVersion) {
        Integer promoted = INSTANCE.get(extension);
        return promoted != null && apiVersion >= promoted;
    }

    /** Known device extension names for tests and diagnostics. */
    public static Set<String> deviceExtensions() {
        return DEVICE.keySet();
    }

    /** Known instance extension names for tests and diagnostics. */
    public static Set<String> instanceExtensions() {
        return INSTANCE.keySet();
    }

    /** Core version that promoted the extension, or empty when unknown. */
    public static java.util.Optional<String> promotedTo(String extension) {
        Integer v = DEVICE.containsKey(extension) ? DEVICE.get(extension) : INSTANCE.get(extension);
        if (v == null) {
            return java.util.Optional.empty();
        }
        int major = v >>> 22;
        int minor = (v >>> 12) & 0x3FF;
        return java.util.Optional.of(major + "." + minor);
    }
}
