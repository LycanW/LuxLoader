package dev.luxloader.api.debug;

import dev.luxloader.api.frame.FrameContext;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.RenderPass;
import java.util.Map;
import java.util.Optional;

/** Optional pass-boundary observation. Events describe CPU command encoding, not GPU completion. */
public interface RenderObserver {
    enum Phase {
        BEFORE_ENCODE,
        AFTER_ENCODE,
        ENCODE_FAILED
    }

    enum AccessKind {
        READ,
        WRITE,
        READ_WRITE
    }

    record ResourceView(String resourceId, AccessKind access, Optional<FrameGraph.ResourceInfo> description,
                        String currentLayout, int queueFamily) {
        public ResourceView {
            if (resourceId == null || resourceId.isBlank()) {
                throw new IllegalArgumentException("resourceId must not be blank");
            }
            access = access == null ? AccessKind.READ : access;
            description = description == null ? Optional.empty() : description;
            currentLayout = currentLayout == null ? "UNKNOWN" : currentLayout;
        }
    }

    record PassEvent(long frameId, String passId, String passName, Phase phase,
                     Map<String, ResourceView> resources, FrameGraph.PassNode node,
                     FrameGraph graph, FrameContext frame, Throwable failure) {
        public PassEvent {
            if (passId == null || passId.isBlank()) {
                throw new IllegalArgumentException("passId must not be blank");
            }
            passName = passName == null ? "" : passName;
            resources = resources == null ? Map.of() : Map.copyOf(resources);
        }
    }

    void onPassBoundary(PassEvent event);
}
