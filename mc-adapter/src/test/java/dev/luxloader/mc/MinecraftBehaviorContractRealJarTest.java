package dev.luxloader.mc;

import dev.luxloader.api.event.BehaviorEvent;
import dev.luxloader.api.event.ClientEventBatch;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.api.state.ClientStateSnapshot;
import dev.luxloader.core.runtime.RenderDriverImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Checks the reflection and mixin method contract against the locally installed Minecraft 26.3 JAR. */
class MinecraftBehaviorContractRealJarTest {
    @Test
    void behaviorEventReflectionMembersMatchTheInstalledClient() throws Exception {
        var jar = MinecraftClientJar.resolve();
        assumeTrue(jar != null);
        try (var loader = MinecraftClientJar.classLoaderFor(jar)) {
            var stateAccess = new MinecraftClientStateAccess(loader);
            assertDoesNotThrow(() -> new MinecraftBehaviorEventAccess(loader, stateAccess, null));

            Class<?> gameMode = loader.loadClass("net.minecraft.client.multiplayer.MultiPlayerGameMode");
            assertMethod(gameMode, "attack", 2);
            assertMethod(gameMode, "useItem", 2);
            assertMethod(gameMode, "useItemOn", 3);
            assertMethod(gameMode, "startDestroyBlock", 2);
            assertMethod(gameMode, "continueDestroyBlock", 2);
            assertMethod(gameMode, "stopDestroyBlock", 0);
            assertMethod(gameMode, "destroyBlock", 1);

            Class<?> entity = loader.loadClass("net.minecraft.world.entity.Entity");
            assertEquals(void.class, entity.getDeclaredMethod("setOnGround", boolean.class).getReturnType());
            assertEquals(void.class, entity.getDeclaredMethod("setOnGroundWithMovement", boolean.class,
                    boolean.class, loader.loadClass("net.minecraft.world.phys.Vec3")).getReturnType());
            assertEquals(boolean.class, entity.getDeclaredMethod("updateFluidInteraction").getReturnType());
            assertMethod(loader.loadClass("net.minecraft.world.entity.LivingEntity"), "jumpFromGround", 0);
            assertMethod(loader.loadClass("net.minecraft.client.gui.Gui"), "setScreen", 1);
            assertNotNull(loader.loadClass("net.minecraft.client.gui.Gui").getMethod("screen"));
            assertEquals(void.class, loader.loadClass("net.minecraft.world.entity.player.Inventory")
                    .getMethod("setSelectedSlot", int.class).getReturnType());

            Class<?> listener = loader.loadClass("net.minecraft.client.multiplayer.ClientPacketListener");
            for (String name : java.util.List.of("handleDamageEvent", "handleHurtAnimation", "handleSetHealth",
                    "handleBlockUpdate", "handleChunkBlocksUpdate", "handleSetHeldSlot")) {
                assertTrue(hasNamedMethod(listener, name), "Missing ClientPacketListener." + name);
            }
        }
    }

    @Test
    @DisplayName("Adapter preserves a source-captured player identity through the driver queue")
    void behaviorIdentityFlowsFromAdapterIntoQueuedDriverEvent(@TempDir Path configDir) throws Exception {
        var jar = MinecraftClientJar.resolve();
        assumeTrue(jar != null);
        try (var loader = MinecraftClientJar.classLoaderFor(jar)) {
            var stateAccess = new MinecraftClientStateAccess(loader);
            RenderDriverImpl driver = new RenderDriverImpl(configDir);
            driver.initialize(RenderDriver.DeviceRequest.attachedToGame());
            try {
                MinecraftBehaviorEventAccess access = new MinecraftBehaviorEventAccess(loader, stateAccess, driver);
                ClientStateSnapshot.EntityIdentity oldIdentity =
                        new ClientStateSnapshot.EntityIdentity(1L, 7, "same-player", 1L);
                ClientStateSnapshot.EntityIdentity replacementIdentity =
                        new ClientStateSnapshot.EntityIdentity(1L, 7, "same-player", 2L);
                List<ClientEventBatch> batches = new ArrayList<>();
                driver.clientEvents(73L, "example:adapter-test").subscribe(batches::add);
                driver.observeClientStateSafePoint(false, 1L, identitySnapshot(oldIdentity));

                Method capture = Arrays.stream(MinecraftBehaviorEventAccess.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("emitCapturedBehaviorEvent")
                                && method.getParameterCount() == 6)
                        .findFirst().orElse(null);
                boolean accepted;
                if (capture == null) {
                    accepted = driver.captureClientBehaviorEvent(1L, BehaviorEvent.Kind.ATTACK,
                            BehaviorEvent.Phase.REQUESTED, BehaviorEvent.Source.LOCAL_INTENT,
                            MinecraftBehaviorContractRealJarTest::emptyFields);
                } else {
                    capture.setAccessible(true);
                    accepted = (Boolean) capture.invoke(access, 1L, BehaviorEvent.Kind.ATTACK,
                            BehaviorEvent.Phase.REQUESTED, BehaviorEvent.Source.LOCAL_INTENT,
                            Optional.of(oldIdentity), Map.of());
                }
                assertTrue(accepted);
                driver.observeClientStateTick(false, identitySnapshot(replacementIdentity));
                driver.observeClientStateSafePoint(false, 1L, identitySnapshot(replacementIdentity));

                BehaviorEvent event = (BehaviorEvent) batches.getLast().events().getFirst();
                Method identity = Arrays.stream(BehaviorEvent.class.getRecordComponents())
                        .filter(component -> component.getName().equals("playerIdentity"))
                        .map(component -> component.getAccessor()).findFirst().orElse(null);
                assertNotNull(identity, "BehaviorEvent must retain the T2 identity captured at its source");
                assertEquals(Optional.of(oldIdentity), identity.invoke(event));
                assertEquals(replacementIdentity, batches.getLast().snapshot().player().identity());
            } finally {
                driver.close();
            }
        }
    }

    private static EventValue.ObjectValue emptyFields() {
        return new EventValue.ObjectValue(Map.of());
    }

    private static java.util.function.BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot>
    identitySnapshot(ClientStateSnapshot.EntityIdentity identity) {
        return (sequence, time) -> new ClientStateSnapshot(sequence, time,
                new ClientStateSnapshot.WorldSession(1L, true, "minecraft:overworld"),
                ClientStateSnapshot.Environment.UNAVAILABLE,
                new ClientStateSnapshot.Player(true, identity, 0d, 64d, 0d, 0f, 0f,
                        0d, 0d, 0d, true, false, false, false, false,
                        ClientStateSnapshot.Pose.STANDING, false, 0f, 0f, false, -1,
                        ClientStateSnapshot.ItemDescription.EMPTY, ClientStateSnapshot.ItemDescription.EMPTY));
    }

    private static void assertMethod(Class<?> type, String name, int parameterCount) {
        assertTrue(java.util.Arrays.stream(type.getMethods())
                .anyMatch(method -> method.getName().equals(name)
                        && method.getParameterCount() == parameterCount),
                "Missing " + type.getName() + "." + name + " with " + parameterCount + " arguments");
    }

    private static boolean hasNamedMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)) return true;
            }
        }
        return false;
    }
}
