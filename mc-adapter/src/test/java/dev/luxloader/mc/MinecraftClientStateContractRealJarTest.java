package dev.luxloader.mc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies the state sampler's reflection surface against the locally installed 26.3 client JAR. */
class MinecraftClientStateContractRealJarTest {
    @Test
    void clientTickPauseRespawnAndStateMembersMatchTheInstalledClient() throws Exception {
        var jar = MinecraftClientJar.resolve();
        assumeTrue(jar != null);
        try (var loader = MinecraftClientJar.classLoaderFor(jar)) {
            Class<?> minecraft = loader.loadClass("net.minecraft.client.Minecraft");
            assertEquals(void.class, minecraft.getMethod("tick").getReturnType());
            assertEquals(void.class, minecraft.getDeclaredMethod("runTick", boolean.class).getReturnType());
            assertEquals(boolean.class, minecraft.getMethod("isPaused").getReturnType());
            assertNotNull(minecraft.getMethod("getConnection"));
            assertEquals("net.minecraft.client.multiplayer.ClientLevel", minecraft.getField("level").getType().getName());
            assertEquals("net.minecraft.client.player.LocalPlayer", minecraft.getField("player").getType().getName());

            Class<?> level = loader.loadClass("net.minecraft.world.level.Level");
            assertNotNull(level.getMethod("dimension"));
            assertEquals(long.class, level.getMethod("getDefaultClockTime").getReturnType());
            assertEquals(float.class, level.getMethod("getRainLevel", float.class).getReturnType());
            assertEquals(float.class, level.getMethod("getThunderLevel", float.class).getReturnType());
            assertNotNull(level.getMethod("getBiome", loader.loadClass("net.minecraft.core.BlockPos")));
            assertNotNull(loader.loadClass("net.minecraft.resources.ResourceKey").getMethod("identifier"));
            assertNotNull(loader.loadClass("net.minecraft.core.Registry").getMethod("getKey", Object.class));
            assertNotNull(loader.loadClass("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM"));

            Class<?> packetListener = loader.loadClass("net.minecraft.client.multiplayer.ClientPacketListener");
            assertNotNull(packetListener.getMethod("handleLogin",
                    loader.loadClass("net.minecraft.network.protocol.game.ClientboundLoginPacket")));
            assertNotNull(packetListener.getMethod("handleRespawn",
                    loader.loadClass("net.minecraft.network.protocol.game.ClientboundRespawnPacket")));

            Class<?> entity = loader.loadClass("net.minecraft.world.entity.Entity");
            for (String name : java.util.List.of("getId", "getUUID", "getX", "getY", "getZ", "getXRot",
                    "getYRot", "getDeltaMovement", "onGround", "isSprinting", "isSwimming", "getPose",
                    "blockPosition")) {
                assertNotNull(java.util.Arrays.stream(entity.getMethods()).filter(method -> method.getName().equals(name))
                        .findFirst().orElse(null), "Missing Entity." + name);
            }
            assertDoesNotThrow(() -> new MinecraftClientStateAccess(loader));
        }
    }
}
