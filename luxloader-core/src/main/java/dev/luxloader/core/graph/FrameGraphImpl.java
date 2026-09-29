package dev.luxloader.core.graph;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.gpu.AcceleratorHandle;
import dev.luxloader.api.gpu.GpuDevice;
import dev.luxloader.api.gpu.ImageHandle;
import dev.luxloader.api.pipeline.StageKind;
import dev.luxloader.api.pipeline.FrameGraph;
import dev.luxloader.api.pipeline.RenderPass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-frame graph implementation combining pass declarations and the resource directory. Create a
 * fresh instance each frame to prevent state leaks; pipelines own persistent cross-frame state and
 * release it on switching.
 */
public final class FrameGraphImpl implements FrameGraph {

    private final int graphicsQueueFamily;
    private final boolean hostFrameRecording;

    public FrameGraphImpl() {
        this(0, false);
    }

    /** A host frame records into Minecraft's graphics submission only. */
    public FrameGraphImpl(int graphicsQueueFamily, boolean hostFrameRecording) {
        if (graphicsQueueFamily < 0) {
            throw new IllegalArgumentException("Invalid graphics queue family");
        }
        this.graphicsQueueFamily = graphicsQueueFamily;
        this.hostFrameRecording = hostFrameRecording;
    }

    /** Selects the resource scope used to describe images published by the current pass. */
    public void useResourceProvider(dev.luxloader.api.pipeline.GpuResourceProvider provider) {
        activeResourceProvider = provider;
    }

    private final Map<String, ImageHandle> textures = new LinkedHashMap<>();
    private final Map<String, GpuDevice.Handle> buffers = new LinkedHashMap<>();
    private final Map<String, AcceleratorHandle> accelStructs = new LinkedHashMap<>();
    private final Map<String, String> resourceDescriptions = new LinkedHashMap<>();
    private final Map<String, FrameGraph.ResourceInfo> resourceInfos = new LinkedHashMap<>();
    private dev.luxloader.api.pipeline.GpuResourceProvider activeResourceProvider;
    private long nextContentVersion;
    private final Set<String> producedThisFrame = new LinkedHashSet<>();
    private final Set<String> importedResources = new LinkedHashSet<>();
    private final List<FrameGraphCompiler.Node> nodes = new ArrayList<>();

    private FrameGraph.Compiled compiled = new FrameGraph.Compiled(List.of(), 0, List.of(), List.of(), true, "");
    private int sequence;

    // Loader resource registration.

    /** Registers a host texture such as color, depth or motion. */
    public FrameGraphImpl provide(String name, ImageHandle image, String description) {
        if (image != null && !image.isNull()) {
            textures.put(name, image);
            importedResources.add(name);
            describeImage(name, image, 0L);
        }
        resourceDescriptions.put(name, description == null ? "" : description);
        return this;
    }

    /** Registers a buffer resource. */
    public FrameGraphImpl provideBuffer(String name, GpuDevice.Handle buffer, String description) {
        if (buffer != null && !buffer.isNull()) {
            buffers.put(name, buffer);
            importedResources.add(name);
        }
        resourceDescriptions.put(name, description == null ? "" : description);
        return this;
    }

    /** Registers an acceleration structure. */
    public FrameGraphImpl provideAccel(String name, AcceleratorHandle accel, String description) {
        if (accel != null && accel.supported()) {
            accelStructs.put(name, accel);
            importedResources.add(name);
        }
        resourceDescriptions.put(name, description == null ? "" : description);
        return this;
    }

    /** Records that a pass writes a resource this frame. */
    public FrameGraphImpl markProduced(String name) {
        producedThisFrame.add(name);
        return this;
    }

    /** Stores compilation results. */
    public void setCompiled(FrameGraph.Compiled result) {
        this.compiled = result == null
                ? new FrameGraph.Compiled(List.of(), 0, List.of(), List.of(), false, tr("No compilation result"))
                : result;
    }

    /**
     * Resolves dependencies only after all passes have been added; dependsOn and before record intents
     * until their target nodes exist.
     */
    public void resolveDependencies() {
        Map<RenderPass, FrameGraphCompiler.Node> byPass = new LinkedHashMap<>();
        for (FrameGraphCompiler.Node n : nodes) {
            byPass.putIfAbsent(n.pass(), n);
        }
        for (FrameGraphCompiler.Node n : nodes) {
            n.resolveDependencies(byPass);
        }
        // Resolve before constraints after building byPass, since they add edges to other nodes.
        for (FrameGraphCompiler.Node n : nodes) {
            n.resolveSuccessors(byPass);
        }
    }

    /** Node list for the compiler. */
    public List<FrameGraphCompiler.Node> nodes() {
        return List.copyOf(nodes);
    }

    /** Resources with an initial value before any pass in this graph runs. */
    public Set<String> importedResources() {
        return Set.copyOf(importedResources);
    }

    // FrameGraph implementation.

    @Override
    public PassNode addPass(RenderPass pass) {
        // Create disabled nodes to preserve stable sequence numbers, then exclude them during compilation.
        FrameGraphCompiler.Node node = new FrameGraphCompiler.Node(
                pass.id(), pass, pass.kind(), 0, sequence++, queueFamilyOf(pass));
        if (!pass.enabled()) {
            node.enabled(false);
        } else {
            // Only enabled passes contribute outputs to the current frame.
            for (String out : pass.outputs()) {
                producedThisFrame.add(out);
            }
        }
        nodes.add(node);
        return node;
    }

    /**
     * Host-owned resources are EXCLUSIVE to the graphics queue family, as checked in Minecraft 26.3
     * texture/buffer/surface/transient allocation paths. Only loader-created resources can use concurrent
     * sharing; touching host resources on another family is invalid. frame.history is loader-owned and
     * concurrently shared, so it is intentionally excluded.
     */
    private final java.util.Set<String> foreignResources = new java.util.LinkedHashSet<>();

    /** Pass names forced to graphics by host resource access, for diagnostics. */
    private final java.util.List<String> queueFamilyOverridden = new java.util.ArrayList<>();

    /** Marks a graphics-exclusive host resource. */
    public void markForeign(String resourceName) {
        if (resourceName != null && !resourceName.isBlank()) {
            foreignResources.add(resourceName);
        }
    }

    /** Passes forced to graphics, for diagnostics. */
    public java.util.List<String> queueFamilyOverridden() {
        return java.util.List.copyOf(queueFamilyOverridden);
    }

    /**
     * Effective queue family: honor a non-graphics request only if no host resource is accessed, otherwise
     * use graphics family zero. Correct serialized execution is preferable to undefined cross-family
     * exclusive access.
     */
    private int queueFamilyOf(RenderPass pass) {
        try {
            if (hostFrameRecording) {
                return graphicsQueueFamily;
            }
            var q = pass.queue();
            if (q == null) {
                return graphicsQueueFamily;
            }
            int family = q.familyIndex();
            if (family == graphicsQueueFamily) {
                return graphicsQueueFamily;
            }
            for (String input : pass.inputs()) {
                if (foreignResources.contains(input)) {
                    // Bound this report-once list because addPass runs every frame.
                    if (queueFamilyOverridden.size() < 16) {
                        queueFamilyOverridden.add(pass.name() + tr(" (reads host resource ") + input + "）");
                    }
                    return graphicsQueueFamily;
                }
            }
            return family;
        } catch (RuntimeException e) {
            // Fall back to graphics if queue lookup fails during encoding instead of failing the frame.
            return graphicsQueueFamily;
        }
    }

    @Override
    public void define(String resourceName, String description) {
        FrameGraph.PassNode.requireName(resourceName);
        resourceDescriptions.put(resourceName, description == null ? "" : description);
    }

    /**
     * Publishes pass-produced textures for named consumption by later passes, including across queue
     * batches. See FrameGraph.publish.
     */
    @Override
    public void publish(String resourceName, ImageHandle image) {
        publish(resourceName, image, Math.incrementExact(nextContentVersion));
    }

    @Override
    public void publish(String resourceName, ImageHandle image, long contentVersion) {
        FrameGraph.PassNode.requireName(resourceName);
        if (image != null && !image.isNull()) {
            textures.put(resourceName, image);
            producedThisFrame.add(resourceName);
            nextContentVersion = Math.max(nextContentVersion, contentVersion);
            describeImage(resourceName, image, contentVersion);
        }
    }

    private void describeImage(String name, ImageHandle image, long contentVersion) {
        var info = activeResourceProvider == null
                ? Optional.<dev.luxloader.api.pipeline.GpuResourceProvider.ImageInfo>empty()
                : activeResourceProvider.imageInfo(image);
        if (info.isPresent()) {
            var value = info.get();
            resourceInfos.put(name, new FrameGraph.ResourceInfo(name, value.ownerId(), value.ownership(),
                    contentVersion == 0 ? value.contentVersion() : contentVersion, value.description(),
                    "", graphicsQueueFamily, "TIGHT_ROW_MAJOR_MIP0_LAYER0", Map.of(), List.of()));
        } else {
            resourceInfos.put(name, new FrameGraph.ResourceInfo(name, "",
                    dev.luxloader.api.pipeline.GpuResourceProvider.Ownership.UNKNOWN,
                    contentVersion, null, "", graphicsQueueFamily, "", Map.of(),
                    List.of("Image description or ownership is not registered")));
        }
    }

    @Override
    public Optional<ImageHandle> texture(String resourceName) {
        return Optional.ofNullable(textures.get(resourceName));
    }

    @Override
    public Optional<FrameGraph.ResourceInfo> resourceInfo(String resourceName) {
        return Optional.ofNullable(resourceInfos.get(resourceName));
    }

    @Override
    public Optional<GpuDevice.Handle> buffer(String resourceName) {
        return Optional.ofNullable(buffers.get(resourceName));
    }

    @Override
    public Optional<AcceleratorHandle> accel(String resourceName) {
        return Optional.ofNullable(accelStructs.get(resourceName));
    }

    @Override
    public Set<String> resourceNames() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(textures.keySet());
        all.addAll(buffers.keySet());
        all.addAll(accelStructs.keySet());
        all.addAll(resourceDescriptions.keySet());
        return Set.copyOf(all);
    }

    @Override
    public boolean isProducedThisFrame(String resourceName) {
        return producedThisFrame.contains(resourceName);
    }

    @Override
    public Compiled compiled() {
        return compiled;
    }

    /**
     * All resources written this frame, including explicit node declarations and pass outputs, for graph
     * dumps and diagnostics even when absent from define().
     */
    public Set<String> producedResources() {
        return Set.copyOf(producedThisFrame);
    }

    // Diagnostics.

    /** Formats the graph for diagnostics and verbose logs. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        Set<String> allNames = new LinkedHashSet<>(resourceNames());
        allNames.addAll(producedThisFrame);
        sb.append(tr("Frame graph resources (")).append(allNames.size()).append("):").append(System.lineSeparator());
        for (String name : allNames) {
            String kind = textures.containsKey(name) ? "texture"
                    : buffers.containsKey(name) ? "buffer"
                    : accelStructs.containsKey(name) ? "accel" : "declared";
            sb.append(String.format("  %-16s %-9s %s%s%n", name, kind,
                    resourceDescriptions.getOrDefault(name, ""),
                    producedThisFrame.contains(name) ? tr(" [produced this frame]") : ""));
        }
        sb.append(tr("Compilation: ")).append(compiled.valid() ? tr("valid") : tr("invalid (") + compiled.error() + ")")
                .append(tr(" nodes=")).append(compiled.ordered().size())
                .append(tr(" barriers~")).append(compiled.barriers())
                .append(tr(" queue batches=")).append(compiled.queueGroups().size())
                .append(System.lineSeparator());
        int i = 0;
        for (PassNode n : compiled.ordered()) {
            sb.append(String.format("  %2d. %-52s %-22s %s%n", ++i, n.nodeId(), n.kind(), n.pass().name()));
        }
        for (String w : compiled.warnings()) {
            sb.append(tr("  Warning: ")).append(w).append(System.lineSeparator());
        }
        return sb.toString();
    }
}
