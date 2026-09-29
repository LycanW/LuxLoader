package dev.luxloader.mc;

import dev.luxloader.api.state.ClientStateSnapshot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Version-specific Minecraft state extraction. Only immutable API values leave this adapter; the
 * opaque object references below are retained solely to detect lifetime replacement.
 */
public final class MinecraftClientStateAccess {
    private final Field levelField;
    private final Field playerField;
    private final Method connection;
    private final Method paused;
    private final Method isSameThread;
    private final Method dimension;
    private final Method defaultClockTime;
    private final Method rainLevel;
    private final Method thunderLevel;
    private final Method getBiome;
    private final Method resourceKeyIdentifier;
    private final Method holderRegisteredName;
    private final Method entityId;
    private final Method entityUuid;
    private final Method x;
    private final Method y;
    private final Method z;
    private final Method xRot;
    private final Method yRot;
    private final Method deltaMovement;
    private final Method onGround;
    private final Method inWater;
    private final Method sprinting;
    private final Method swimming;
    private final Method pose;
    private final Method blockPosition;
    private final Method crouching;
    private final Method underwater;
    private final Method health;
    private final Method maxHealth;
    private final Method mainHand;
    private final Method offHand;
    private final Method inventory;
    private final Method selectedSlot;
    private final Method itemInHand;
    private final Method itemIsEmpty;
    private final Method itemCount;
    private final Method itemDamage;
    private final Method itemMaxDamage;
    private final Method item;
    private final Field itemRegistryField;
    private final Method itemKey;
    private final Method vecX;
    private final Method vecY;
    private final Method vecZ;
    private final Method blockX;
    private final Method blockY;
    private final Method blockZ;

    private final ClientIdentityTracker identities = new ClientIdentityTracker();
    private String currentDimension = "";
    private ClientIdentityTracker.Lifecycle latestLifecycle =
            new ClientIdentityTracker.Lifecycle(0L, false, "", 0L, null, false);

    public MinecraftClientStateAccess(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> minecraft = loader.loadClass("net.minecraft.client.Minecraft");
        Class<?> level = loader.loadClass("net.minecraft.world.level.Level");
        Class<?> player = loader.loadClass("net.minecraft.client.player.LocalPlayer");
        Class<?> entity = loader.loadClass("net.minecraft.world.entity.Entity");
        Class<?> living = loader.loadClass("net.minecraft.world.entity.LivingEntity");
        Class<?> blockPos = loader.loadClass("net.minecraft.core.BlockPos");
        Class<?> inventoryType = loader.loadClass("net.minecraft.world.entity.player.Inventory");
        Class<?> itemStack = loader.loadClass("net.minecraft.world.item.ItemStack");
        Class<?> vec3 = loader.loadClass("net.minecraft.world.phys.Vec3");

        levelField = minecraft.getField("level");
        playerField = minecraft.getField("player");
        connection = minecraft.getMethod("getConnection");
        paused = minecraft.getMethod("isPaused");
        isSameThread = minecraft.getMethod("isSameThread");
        dimension = level.getMethod("dimension");
        defaultClockTime = level.getMethod("getDefaultClockTime");
        rainLevel = level.getMethod("getRainLevel", float.class);
        thunderLevel = level.getMethod("getThunderLevel", float.class);
        getBiome = level.getMethod("getBiome", blockPos);
        resourceKeyIdentifier = loader.loadClass("net.minecraft.resources.ResourceKey").getMethod("identifier");
        holderRegisteredName = loader.loadClass("net.minecraft.core.Holder").getMethod("getRegisteredNameIfPresent");

        entityId = entity.getMethod("getId");
        entityUuid = entity.getMethod("getUUID");
        x = entity.getMethod("getX");
        y = entity.getMethod("getY");
        z = entity.getMethod("getZ");
        xRot = entity.getMethod("getXRot");
        yRot = entity.getMethod("getYRot");
        deltaMovement = entity.getMethod("getDeltaMovement");
        onGround = entity.getMethod("onGround");
        inWater = entity.getMethod("isInWater");
        sprinting = entity.getMethod("isSprinting");
        swimming = entity.getMethod("isSwimming");
        pose = entity.getMethod("getPose");
        blockPosition = entity.getMethod("blockPosition");

        crouching = player.getMethod("isShiftKeyDown");
        underwater = player.getMethod("isUnderWater");
        health = living.getMethod("getHealth");
        maxHealth = living.getMethod("getMaxHealth");
        mainHand = living.getMethod("getMainHandItem");
        offHand = living.getMethod("getOffhandItem");
        inventory = player.getMethod("getInventory");
        selectedSlot = inventoryType.getMethod("getSelectedSlot");
        itemInHand = player.getMethod("getItemInHand", loader.loadClass("net.minecraft.world.InteractionHand"));

        itemIsEmpty = itemStack.getMethod("isEmpty");
        itemCount = itemStack.getMethod("getCount");
        itemDamage = itemStack.getMethod("getDamageValue");
        itemMaxDamage = itemStack.getMethod("getMaxDamage");
        item = itemStack.getMethod("getItem");
        itemRegistryField = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries")
                .getField("ITEM");
        itemKey = loader.loadClass("net.minecraft.core.Registry").getMethod("getKey", Object.class);

        vecX = vec3.getMethod("x");
        vecY = vec3.getMethod("y");
        vecZ = vec3.getMethod("z");
        blockX = blockPos.getMethod("getX");
        blockY = blockPos.getMethod("getY");
        blockZ = blockPos.getMethod("getZ");
    }

    /** The 26.3 game-loop pause flag. */
    public boolean isPaused(Object minecraft) throws ReflectiveOperationException {
        return (boolean) paused.invoke(minecraft);
    }

    /** Returns whether a source hook is running on Minecraft's client update thread. */
    public boolean isClientThread(Object minecraft) throws ReflectiveOperationException {
        return (boolean) isSameThread.invoke(minecraft);
    }

    /** Current local player reference for identity comparison inside the adapter only. */
    public Object localPlayer(Object minecraft) throws ReflectiveOperationException {
        return playerField.get(minecraft);
    }

    public boolean isLocalPlayer(Object minecraft, Object entity) throws ReflectiveOperationException {
        return entity != null && entity == playerField.get(minecraft);
    }

    public int entityId(Object entity) throws ReflectiveOperationException {
        return ((Number) entityId.invoke(entity)).intValue();
    }

    public boolean isOnGround(Object entity) throws ReflectiveOperationException {
        return (boolean) onGround.invoke(entity);
    }

    public boolean isInWater(Object entity) throws ReflectiveOperationException {
        return (boolean) inWater.invoke(entity);
    }

    public int selectedSlot(Object player) throws ReflectiveOperationException {
        Object playerInventory = inventory.invoke(player);
        return ((Number) selectedSlot.invoke(playerInventory)).intValue();
    }

    /** Whether an observed inventory setter belongs to the current local player. */
    public boolean isLocalPlayerInventory(Object minecraft, Object candidate)
            throws ReflectiveOperationException {
        Object player = playerField.get(minecraft);
        return player != null && inventory.invoke(player) == candidate;
    }

    public Object itemInHand(Object player, Object hand) throws ReflectiveOperationException {
        return itemInHand.invoke(player, hand);
    }

    /** Copies the same limited item descriptor used by the state stream. */
    public ClientStateSnapshot.ItemDescription describeItem(Object stack) {
        try {
            return readItem(stack);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return ClientStateSnapshot.ItemDescription.EMPTY;
        }
    }

    /**
     * Cheap per-loop identity tracking. It reads only the current level, connection and player
     * references; it does not walk entities, chunks, or inventory contents.
     */
    private synchronized ClientIdentityTracker.Lifecycle observeLifecycle(Object minecraft)
            throws ReflectiveOperationException {
        Object level = levelField.get(minecraft);
        Object currentConnection = connection.invoke(minecraft);
        Object player = playerField.get(minecraft);
        currentDimension = level == null ? "" : dimensionId(level);
        latestLifecycle = identities.observe(level, currentConnection, currentDimension, player);
        return latestLifecycle;
    }

    /** Current opaque world-session generation for the host's safe-point boundary. */
    public long observeSessionGeneration(Object minecraft) throws ReflectiveOperationException {
        return observeLifecycle(minecraft).sessionGeneration();
    }

    /** Current local-player identity, including the object generation tracked by the state bridge. */
    public synchronized ClientStateSnapshot.EntityIdentity observePlayerIdentity(Object minecraft)
            throws ReflectiveOperationException {
        ClientIdentityTracker.Lifecycle lifecycle = observeLifecycle(minecraft);
        Object player = lifecycle.currentPlayer();
        if (player == null || !lifecycle.worldLoaded()) return null;
        return new ClientStateSnapshot.EntityIdentity(lifecycle.sessionGeneration(),
                ((Number) entityId.invoke(player)).intValue(), String.valueOf(entityUuid.invoke(player)),
                lifecycle.playerGeneration());
    }

    /** Copy one sampled game state into values safe to retain beyond the Minecraft callback. */
    public synchronized ClientStateSnapshot sample(Object minecraft, long sampleSequence,
            ClientStateSnapshot.LogicalTime time) throws ReflectiveOperationException {
        ClientIdentityTracker.Lifecycle lifecycle = observeLifecycle(minecraft);
        Object level = levelField.get(minecraft);
        Object player = lifecycle.currentPlayer();
        ClientStateSnapshot.WorldSession world = new ClientStateSnapshot.WorldSession(
                lifecycle.sessionGeneration(), lifecycle.worldLoaded(), lifecycle.dimensionId());

        ClientStateSnapshot.Environment environment = readEnvironment(level, player, lifecycle.dimensionId());
        ClientStateSnapshot.Player playerState = readPlayerSafely(player, lifecycle);
        return new ClientStateSnapshot(sampleSequence, time, world, environment, playerState);
    }

    private ClientStateSnapshot.Environment readEnvironment(Object level, Object player, String dimensionId) {
        if (level == null) return ClientStateSnapshot.Environment.UNAVAILABLE;
        try {
            long timeOfDay = ((Number) defaultClockTime.invoke(level)).longValue();
            float rain = ((Number) rainLevel.invoke(level, 1f)).floatValue();
            float thunder = ((Number) thunderLevel.invoke(level, 1f)).floatValue();
            return new ClientStateSnapshot.Environment(true, dimensionId, timeOfDay, rain, thunder,
                    readBiome(level, player));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return ClientStateSnapshot.Environment.UNAVAILABLE;
        }
    }

    private ClientStateSnapshot.BiomeSample readBiome(Object level, Object player) {
        if (player == null) return ClientStateSnapshot.BiomeSample.UNAVAILABLE;
        try {
            Object block = blockPosition.invoke(player);
            int bx = ((Number) blockX.invoke(block)).intValue();
            int by = ((Number) blockY.invoke(block)).intValue();
            int bz = ((Number) blockZ.invoke(block)).intValue();
            Object holder = getBiome.invoke(level, block);
            Object optionalValue = holderRegisteredName.invoke(holder);
            if (!(optionalValue instanceof Optional<?> optional) || optional.isEmpty()) {
                return ClientStateSnapshot.BiomeSample.UNAVAILABLE;
            }
            String biomeId = String.valueOf(optional.get());
            return new ClientStateSnapshot.BiomeSample(!biomeId.isBlank(), biomeId, bx, by, bz);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return ClientStateSnapshot.BiomeSample.UNAVAILABLE;
        }
    }

    private ClientStateSnapshot.Player readPlayerSafely(Object player,
            ClientIdentityTracker.Lifecycle lifecycle) {
        if (player == null || !lifecycle.worldLoaded()) return ClientStateSnapshot.Player.UNAVAILABLE;
        try {
            return readPlayer(player, lifecycle);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return ClientStateSnapshot.Player.UNAVAILABLE;
        }
    }

    private ClientStateSnapshot.Player readPlayer(Object player, ClientIdentityTracker.Lifecycle lifecycle)
            throws ReflectiveOperationException {
        int id = ((Number) entityId.invoke(player)).intValue();
        String uuid = String.valueOf(entityUuid.invoke(player));
        ClientStateSnapshot.EntityIdentity identity = new ClientStateSnapshot.EntityIdentity(
                lifecycle.sessionGeneration(), id, uuid, lifecycle.playerGeneration());
        Object movement = deltaMovement.invoke(player);
        double vx = ((Number) vecX.invoke(movement)).doubleValue();
        double vy = ((Number) vecY.invoke(movement)).doubleValue();
        double vz = ((Number) vecZ.invoke(movement)).doubleValue();
        Object rawPose = pose.invoke(player);
        ClientStateSnapshot.Pose mappedPose;
        try {
            mappedPose = ClientStateSnapshot.Pose.valueOf(String.valueOf(rawPose).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mappedPose = ClientStateSnapshot.Pose.UNKNOWN;
        }
        int slot = -1;
        ClientStateSnapshot.ItemDescription main = ClientStateSnapshot.ItemDescription.EMPTY;
        ClientStateSnapshot.ItemDescription off = ClientStateSnapshot.ItemDescription.EMPTY;
        boolean inventoryValid = false;
        try {
            Object inventoryValue = inventory.invoke(player);
            slot = ((Number) selectedSlot.invoke(inventoryValue)).intValue();
            main = readItem(mainHand.invoke(player));
            off = readItem(offHand.invoke(player));
            inventoryValid = true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Core movement state remains useful when an optional item member is unavailable.
        }
        boolean healthValid = false;
        float currentHealth = 0f;
        float maximumHealth = 0f;
        try {
            currentHealth = ((Number) health.invoke(player)).floatValue();
            maximumHealth = ((Number) maxHealth.invoke(player)).floatValue();
            healthValid = Float.isFinite(currentHealth) && Float.isFinite(maximumHealth);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Health remains explicitly unavailable.
        }
        return new ClientStateSnapshot.Player(true, identity,
                number(x.invoke(player)), number(y.invoke(player)), number(z.invoke(player)),
                ((Number) xRot.invoke(player)).floatValue(), ((Number) yRot.invoke(player)).floatValue(),
                vx, vy, vz,
                (boolean) onGround.invoke(player), (boolean) crouching.invoke(player),
                (boolean) sprinting.invoke(player), (boolean) swimming.invoke(player),
                (boolean) underwater.invoke(player), mappedPose,
                healthValid, currentHealth, maximumHealth,
                inventoryValid, slot, main, off);
    }

    private ClientStateSnapshot.ItemDescription readItem(Object stack) throws ReflectiveOperationException {
        if (stack == null || (boolean) itemIsEmpty.invoke(stack)) return ClientStateSnapshot.ItemDescription.EMPTY;
        Object itemValue = item.invoke(stack);
        String itemId = String.valueOf(itemKey.invoke(itemRegistryField.get(null), itemValue));
        return new ClientStateSnapshot.ItemDescription(itemId,
                Math.max(0, ((Number) itemCount.invoke(stack)).intValue()),
                Math.max(0, ((Number) itemDamage.invoke(stack)).intValue()),
                Math.max(0, ((Number) itemMaxDamage.invoke(stack)).intValue()));
    }

    private String dimensionId(Object level) throws ReflectiveOperationException {
        Object key = dimension.invoke(level);
        Object identifier = resourceKeyIdentifier.invoke(key);
        return String.valueOf(identifier);
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
