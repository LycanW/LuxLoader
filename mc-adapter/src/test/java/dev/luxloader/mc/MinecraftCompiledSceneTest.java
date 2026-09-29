package dev.luxloader.mc;

import dev.luxloader.api.scene.CompiledSceneMesh;
import dev.luxloader.api.scene.MeshChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftCompiledSceneTest {

    @Test void lateCaptureOfPreviouslyUnknownSectionKeepsHostPathUntilRebuildOrRemoval() {
        var scene = new MinecraftCompiledScene();
        scene.resourceReloadStarted();
        long current = MinecraftResourceAccess.currentState().generation();
        assertEquals(current, scene.resourceGeneration().orElseThrow());
        scene.captureIncomplete(90);
        assertEquals(Long.MIN_VALUE, scene.resourceGeneration().orElseThrow());
        Object replacement = new Object();
        scene.publish(90, "SOLID", mesh("late-replacement"), replacement);
        scene.acceptSection(90, replacement, java.util.Set.of("SOLID"));
        assertEquals(current, scene.resourceGeneration().orElseThrow());
        scene.captureIncomplete(100);
        assertEquals(Long.MIN_VALUE, scene.resourceGeneration().orElseThrow());
        scene.removeSection(100);
        assertEquals(current, scene.resourceGeneration().orElseThrow());
    }

    @Test void missingOrPartialCaptureCannotMakeTheFeedReadyButTrueEmptyAndHealthyReplacementCan() {
        var scene = new MinecraftCompiledScene();
        Object missing = new Object();
        scene.acceptSection(10, missing, java.util.Set.of("SOLID"));
        assertEquals(Long.MIN_VALUE, scene.resourceGeneration().orElseThrow());
        assertTrue(scene.snapshot().meshes().isEmpty());
        Object partial = new Object();
        scene.publish(10,"SOLID",mesh("solid"),partial);
        scene.acceptSection(10,partial,java.util.Set.of("SOLID","CUTOUT"));
        assertEquals(Long.MIN_VALUE,scene.resourceGeneration().orElseThrow());
        assertTrue(scene.snapshot().meshes().isEmpty());
        scene.publish(10,"CUTOUT",mesh("cutout"),partial);
        scene.acceptSection(10,partial,java.util.Set.of("SOLID","CUTOUT"));
        assertEquals(2,scene.snapshot().meshes().size());
        assertEquals(MinecraftResourceAccess.currentState().generation(),scene.resourceGeneration().orElseThrow());
        scene.acceptSection(10,partial,java.util.Set.of("SOLID","CUTOUT"));
        assertEquals(MinecraftResourceAccess.currentState().generation(),scene.resourceGeneration().orElseThrow());
        scene.resourceReloadStarted();
        scene.acceptSection(10,new Object(),java.util.Set.of());
        assertTrue(scene.snapshot().meshes().isEmpty());
        assertEquals(MinecraftResourceAccess.currentState().generation(),scene.resourceGeneration().orElseThrow());
    }

    @Test void resourceRebuildRequiresEveryPreviouslyAcceptedSectionOrAnExplicitRemoval() {
        var scene = new MinecraftCompiledScene();
        Object first = new Object(), second = new Object();
        scene.publish(10, "SOLID", mesh("first"), first); scene.acceptSection(10, first);
        scene.publish(20, "SOLID", mesh("second"), second); scene.acceptSection(20, second);
        long cursor = scene.revision(); scene.resourceReloadStarted();
        assertTrue(scene.changesSince(cursor).reset()); assertTrue(scene.snapshot().meshes().isEmpty());
        assertEquals(Long.MIN_VALUE, scene.resourceGeneration().orElseThrow());
        Object replacement = new Object();
        scene.publish(10, "SOLID", mesh("new"), replacement); scene.acceptSection(10, replacement);
        assertEquals(Long.MIN_VALUE, scene.resourceGeneration().orElseThrow());
        scene.removeSection(20);
        assertEquals(MinecraftResourceAccess.currentState().generation(), scene.resourceGeneration().orElseThrow());
        scene.resourceReloadStarted(); scene.clear();
        assertEquals(MinecraftResourceAccess.currentState().generation(), scene.resourceGeneration().orElseThrow());
    }

    private static CompiledSceneMesh mesh(String id) {
        return new CompiledSceneMesh(id, MeshChunk.Kind.TERRAIN_OPAQUE, 0, 0, 0, 12,
                List.of(new CompiledSceneMesh.Attribute("Position", 0, "RGB32_FLOAT")),
                new byte[48], new byte[0], MeshChunk.IndexType.UNSIGNED_SHORT, 6, true);
    }

    @Test
    void acceptedSectionChangesPublishByRevisionAndResetEvicts() {
        MinecraftCompiledScene scene = new MinecraftCompiledScene();
        var empty = scene.snapshot();
        assertSame(empty, scene.snapshot());
        scene.publish(10L, "SOLID", mesh("10/solid"));
        var first = scene.snapshot();
        assertEquals(1, first.meshes().size());
        assertEquals(empty.revision() + 1, first.revision());
        assertSame(first, scene.snapshot());
        scene.publish(10L, "SOLID", mesh("10/solid"));
        assertEquals(1, scene.snapshot().meshes().size());
        scene.publish(10L, "TRANSLUCENT", mesh("10/translucent"));
        assertEquals(2, scene.snapshot().meshes().size());
        scene.removeSection(10L);
        assertEquals(0, scene.snapshot().meshes().size());
    }

    @Test
    void consumersReceiveOnlyUnseenGeometryAndTombstones() {
        MinecraftCompiledScene scene = new MinecraftCompiledScene();
        scene.publish(10L, "SOLID", mesh("10/solid"));
        long cursor = scene.snapshot().revision();
        scene.publish(10L, "SOLID", mesh("10/solid"));
        scene.publish(10L, "CUTOUT", mesh("10/cutout"));
        var updated = scene.changesSince(cursor);
        assertEquals(2, updated.upserts().size());
        assertTrue(updated.removals().isEmpty());
        assertTrue(!updated.reset());

        scene.removeSection(10L);
        var removed = scene.changesSince(updated.toRevision());
        assertEquals(java.util.Set.of("10/solid", "10/cutout"), removed.removals());
        assertTrue(removed.upserts().isEmpty());
        assertTrue(scene.changesSince(removed.toRevision()).unchanged());
    }

    @Test
    void worldResetAndMissedHistoryRequireFullResynchronization() {
        MinecraftCompiledScene scene = new MinecraftCompiledScene();
        scene.publish(10L, "SOLID", mesh("10/solid"));
        long cursor = scene.snapshot().revision();
        scene.clear();
        scene.publish(20L, "SOLID", mesh("20/solid"));
        var reset = scene.changesSince(cursor);
        assertTrue(reset.reset());
        assertEquals(List.of("20/solid"), reset.upserts().stream().map(CompiledSceneMesh::id).toList());
        assertTrue(reset.removals().isEmpty());
    }

    @Test
    void pendingSectionIsInvisibleUntilAcceptedAndEmptyReplacementEvictsIt() {
        MinecraftCompiledScene scene = new MinecraftCompiledScene();
        Object first = new Object();
        scene.publish(10L, "SOLID", mesh("10/solid"), first);
        assertTrue(scene.snapshot().meshes().isEmpty());

        scene.acceptSection(10L, first);
        assertEquals(List.of("10/solid"), scene.snapshot().meshes().stream()
                .map(CompiledSceneMesh::id).toList());
        long cursor = scene.revision();

        Object empty = new Object();
        scene.acceptSection(10L, empty);
        assertTrue(scene.snapshot().meshes().isEmpty());
        assertEquals(java.util.Set.of("10/solid"), scene.changesSince(cursor).removals());
    }

    @Test
    void rejectedPendingGenerationCannotReplaceAcceptedSection() {
        MinecraftCompiledScene scene = new MinecraftCompiledScene();
        Object accepted = new Object();
        Object superseded = new Object();
        scene.publish(10L, "SOLID", mesh("10/accepted"), accepted);
        scene.acceptSection(10L, accepted);
        scene.publish(10L, "SOLID", mesh("10/superseded"), superseded);

        assertEquals(List.of("10/accepted"), scene.snapshot().meshes().stream()
                .map(CompiledSceneMesh::id).toList());
        assertEquals(1, scene.snapshot().meshes().size());
    }
}
