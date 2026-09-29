package dev.luxloader.mc;

import dev.luxloader.api.scene.CompiledSceneMesh;
import dev.luxloader.api.scene.SceneGeometryDelta;
import dev.luxloader.api.scene.SceneGeometryFeed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe bridge from Minecraft's accepted section uploads to frame snapshots.
 * Mesh objects are retained until the section is recompiled, reset, or the world
 * changes. Reading a frame only copies references, never re-meshes game blocks.
 */
public final class MinecraftCompiledScene implements SceneGeometryFeed {

    public record Snapshot(long revision, List<CompiledSceneMesh> meshes) { }

    private static final MinecraftCompiledScene INSTANCE = new MinecraftCompiledScene();

    public static MinecraftCompiledScene instance() {
        return INSTANCE;
    }

    private final Map<Long, Map<String, CompiledSceneMesh>> sections = new HashMap<>();
    private final Map<Long, Object> generations = new HashMap<>();
    private record Pending(Object generation, Map<String, CompiledSceneMesh> layers) { }
    private final Map<Long, Pending> pending = new HashMap<>();
    private static final int MAX_CHANGES = 4096;
    private static final long MAX_CHANGE_BYTES = 32L * 1024 * 1024;
    private final java.util.ArrayDeque<Change> changes = new java.util.ArrayDeque<>();
    private long changeBytes;
    private long revision;
    private Snapshot cached = new Snapshot(0L, List.of());

    private record Change(long revision, String id, CompiledSceneMesh mesh, boolean reset) { }

    public synchronized void publish(long sectionNode, String layer, CompiledSceneMesh mesh) {
        publish(sectionNode, layer, mesh, null);
    }

    public synchronized void publish(long sectionNode, String layer, CompiledSceneMesh mesh,
                                     Object generation) {
        if (layer == null || mesh == null) {
            throw new IllegalArgumentException("Section layer and mesh are required");
        }
        if (generation != null) {
            Pending candidate = pending.get(sectionNode);
            if (candidate == null || candidate.generation() != generation) {
                candidate = new Pending(generation, new HashMap<>());
                pending.put(sectionNode, candidate);
            }
            candidate.layers().put(layer, mesh);
            return;
        }
        CompiledSceneMesh old = sections.computeIfAbsent(sectionNode, ignored -> new HashMap<>())
                .put(layer, mesh);
        if (old != null && !old.id().equals(mesh.id())) {
            record(old.id(), null, false);
        }
        record(mesh.id(), mesh, false);
    }

    /** Commit a complete section only when Minecraft adopts that mesh for drawing. */
    public synchronized void acceptSection(long sectionNode, Object generation) {
        if (generation == null || generations.get(sectionNode) == generation) return;
        Pending candidate = pending.get(sectionNode);
        Map<String, CompiledSceneMesh> next = candidate != null
                && candidate.generation() == generation
                ? candidate.layers() : Map.of();
        if (candidate != null && candidate.generation() == generation) {
            pending.remove(sectionNode);
        }
        Map<String, CompiledSceneMesh> previous = sections.getOrDefault(sectionNode, Map.of());
        for (Map.Entry<String, CompiledSceneMesh> old : previous.entrySet()) {
            CompiledSceneMesh replacement = next.get(old.getKey());
            if (replacement == null || !replacement.id().equals(old.getValue().id())) {
                record(old.getValue().id(), null, false);
            }
        }
        if (next.isEmpty()) {
            sections.remove(sectionNode);
        } else {
            sections.put(sectionNode, new HashMap<>(next));
        }
        for (Map.Entry<String, CompiledSceneMesh> entry : next.entrySet()) {
            if (previous.get(entry.getKey()) != entry.getValue()) {
                record(entry.getValue().id(), entry.getValue(), false);
            }
        }
        generations.put(sectionNode, generation);
    }

    public synchronized void removeSection(long sectionNode) {
        removeLayers(sectionNode);
        generations.remove(sectionNode);
        pending.remove(sectionNode);
    }

    public synchronized void clear() {
        if (!sections.isEmpty() || !generations.isEmpty() || !pending.isEmpty()) {
            sections.clear();
            generations.clear();
            pending.clear();
            record(null, null, true);
        }
    }

    private void removeLayers(long sectionNode) {
        Map<String, CompiledSceneMesh> removed = sections.remove(sectionNode);
        if (removed != null) {
            for (CompiledSceneMesh mesh : removed.values()) {
                record(mesh.id(), null, false);
            }
        }
    }

    private void record(String id, CompiledSceneMesh mesh, boolean reset) {
        changes.addLast(new Change(++revision, id, mesh, reset));
        changeBytes += mesh == null ? 0L : (long) mesh.vertexBytes() + mesh.indexBytes();
        while (changes.size() > MAX_CHANGES || changeBytes > MAX_CHANGE_BYTES) {
            CompiledSceneMesh dropped = changes.removeFirst().mesh();
            changeBytes -= dropped == null ? 0L
                    : (long) dropped.vertexBytes() + dropped.indexBytes();
        }
    }

    @Override
    public synchronized SceneGeometryDelta changesSince(long fromRevision) {
        if (fromRevision < 0 || fromRevision > revision) {
            throw new IllegalArgumentException("Invalid scene revision: " + fromRevision);
        }
        if (fromRevision == revision) {
            return new SceneGeometryDelta(fromRevision, revision, false, List.of(), java.util.Set.of());
        }
        boolean missedHistory = changes.isEmpty() || fromRevision < changes.getFirst().revision() - 1;
        boolean crossedReset = !missedHistory && changes.stream()
                .anyMatch(change -> change.revision() > fromRevision && change.reset());
        if (missedHistory || crossedReset) {
            return new SceneGeometryDelta(fromRevision, revision, true, snapshot().meshes(),
                    java.util.Set.of());
        }
        Map<String, CompiledSceneMesh> upserts = new HashMap<>();
        java.util.Set<String> removals = new java.util.HashSet<>();
        for (Change change : changes) {
            if (change.revision() <= fromRevision) continue;
            if (change.mesh() == null) {
                upserts.remove(change.id());
                removals.add(change.id());
            } else {
                removals.remove(change.id());
                upserts.put(change.id(), change.mesh());
            }
        }
        return new SceneGeometryDelta(fromRevision, revision, false,
                List.copyOf(upserts.values()), java.util.Set.copyOf(removals));
    }

    public synchronized Snapshot snapshot() {
        if (cached.revision() != revision) {
            List<CompiledSceneMesh> meshes = new ArrayList<>();
            for (Map<String, CompiledSceneMesh> layers : sections.values()) {
                meshes.addAll(layers.values());
            }
            cached = new Snapshot(revision, List.copyOf(meshes));
        }
        return cached;
    }

    public synchronized long revision() {
        return revision;
    }
}
