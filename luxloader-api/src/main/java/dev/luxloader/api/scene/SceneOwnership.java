package dev.luxloader.api.scene;

import dev.luxloader.api.GpuId;

/**
 * Ownership rule for scene contributions. A contribution carries the ID of the plugin that
 * registered it, and only that plugin may release it or re-register it. The identifier itself must
 * be derived from the owner, so a plugin cannot claim another plugin's namespace even by accident.
 *
 * <p>The rule exists so cleanup can be attributed: when a plugin unloads, the loader removes exactly
 * the contributions that plugin registered. Without it, a replaced plugin instance could keep
 * contributing into the new session, or a second plugin could silently overwrite the first one's
 * registration.
 */
public final class SceneOwnership {

    private SceneOwnership() {
    }

    /**
     * Whether a contribution registered by {@code ownerPluginId} may use {@code contributorId}.
     * The ID must equal the owner ID or be a child of it in the same namespace, so the owner
     * prefix is always recognizable.
     * @param ownerPluginId registering plugin's ID
     * @param contributorId contribution ID
     * @return whether the registration is allowed
     */
    public static boolean isOwnedBy(String ownerPluginId, GpuId contributorId) {
        if (ownerPluginId == null || ownerPluginId.isBlank() || contributorId == null) {
            return false;
        }
        GpuId owner = GpuId.tryParse(ownerPluginId);
        if (owner == null || !owner.namespace().equals(contributorId.namespace())) {
            return false;
        }
        String ownerPath = owner.path();
        String contributorPath = contributorId.path();
        return contributorPath.equals(ownerPath) || contributorPath.startsWith(ownerPath + "/");
    }
}
