package dev.luxloader.mc;

import dev.luxloader.api.event.BehaviorEvent;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.state.ClientStateSnapshot;
import dev.luxloader.core.runtime.RenderDriverImpl;
import dev.luxloader.mc.hooks.ClientBehaviorSignal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Copies version-specific Minecraft behavior signals into bounded, immutable core events. */
public final class MinecraftBehaviorEventAccess {
    private static final int MAX_SECTION_BLOCK_SAMPLES = 16;

    private final MinecraftClientStateAccess stateAccess;
    private final RenderDriverImpl driver;
    private final ClientBehaviorStateTracker tracker = new ClientBehaviorStateTracker();
    /** Source-local immutable copy; only populated during one client-thread signal dispatch. */
    private ClientStateSnapshot.EntityIdentity capturedPlayerIdentity;

    private final Method getInstance;
    private final Field minecraftGui;
    private final Method guiScreen;
    private final Method hitBlockPos;
    private final Method hitDirection;
    private final Method blockPosX;
    private final Method blockPosY;
    private final Method blockPosZ;
    private final Method damageEntityId;
    private final Method damageSourceType;
    private final Method damageCauseId;
    private final Method damageDirectId;
    private final Method damageSourcePosition;
    private final Method hurtEntityId;
    private final Method hurtYaw;
    private final Method healthValue;
    private final Method blockPacketPos;
    private final Method blockPacketState;
    private final Method sectionRunUpdates;
    private final Method heldSlot;
    private final Method vecX;
    private final Method vecY;
    private final Method vecZ;
    private final Method holderRegisteredName;
    private final Field blockRegistry;
    private final Method registryKey;
    private final Method blockStateBlock;
    private final Method blockStateValues;
    private final Method propertyValueProperty;
    private final Method propertyValueName;
    private final Method propertyName;

    public MinecraftBehaviorEventAccess(ClassLoader loader, MinecraftClientStateAccess stateAccess,
                                        RenderDriverImpl driver) throws ReflectiveOperationException {
        this.stateAccess = stateAccess;
        this.driver = driver;
        Class<?> minecraft = loader.loadClass("net.minecraft.client.Minecraft");
        Class<?> gui = loader.loadClass("net.minecraft.client.gui.Gui");
        Class<?> blockPos = loader.loadClass("net.minecraft.core.BlockPos");
        Class<?> damagePacket = loader.loadClass("net.minecraft.network.protocol.game.ClientboundDamageEventPacket");
        Class<?> hurtPacket = loader.loadClass("net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket");
        Class<?> healthPacket = loader.loadClass("net.minecraft.network.protocol.game.ClientboundSetHealthPacket");
        Class<?> blockPacket = loader.loadClass("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
        Class<?> sectionPacket = loader.loadClass("net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket");
        Class<?> heldSlotPacket = loader.loadClass("net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket");
        Class<?> vec3 = loader.loadClass("net.minecraft.world.phys.Vec3");
        Class<?> holder = loader.loadClass("net.minecraft.core.Holder");
        Class<?> registries = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries");
        Class<?> registry = loader.loadClass("net.minecraft.core.Registry");
        Class<?> blockState = loader.loadClass("net.minecraft.world.level.block.state.BlockState");
        Class<?> stateValue = loader.loadClass("net.minecraft.world.level.block.state.properties.Property$Value");
        Class<?> property = loader.loadClass("net.minecraft.world.level.block.state.properties.Property");

        getInstance = minecraft.getMethod("getInstance");
        minecraftGui = minecraft.getField("gui");
        guiScreen = gui.getMethod("screen");
        hitBlockPos = loader.loadClass("net.minecraft.world.phys.BlockHitResult").getMethod("getBlockPos");
        hitDirection = loader.loadClass("net.minecraft.world.phys.BlockHitResult").getMethod("getDirection");
        blockPosX = blockPos.getMethod("getX");
        blockPosY = blockPos.getMethod("getY");
        blockPosZ = blockPos.getMethod("getZ");
        damageEntityId = damagePacket.getMethod("entityId");
        damageSourceType = damagePacket.getMethod("sourceType");
        damageCauseId = damagePacket.getMethod("sourceCauseId");
        damageDirectId = damagePacket.getMethod("sourceDirectId");
        damageSourcePosition = damagePacket.getMethod("sourcePosition");
        hurtEntityId = hurtPacket.getMethod("id");
        hurtYaw = hurtPacket.getMethod("yaw");
        healthValue = healthPacket.getMethod("getHealth");
        blockPacketPos = blockPacket.getMethod("getPos");
        blockPacketState = blockPacket.getMethod("getBlockState");
        sectionRunUpdates = sectionPacket.getMethod("runUpdates", BiConsumer.class);
        heldSlot = heldSlotPacket.getMethod("slot");
        vecX = vec3.getMethod("x");
        vecY = vec3.getMethod("y");
        vecZ = vec3.getMethod("z");
        holderRegisteredName = holder.getMethod("getRegisteredNameIfPresent");
        blockRegistry = registries.getField("BLOCK");
        registryKey = registry.getMethod("getKey", Object.class);
        blockStateBlock = blockState.getMethod("getBlock");
        blockStateValues = blockState.getMethod("getValues");
        propertyValueProperty = stateValue.getMethod("property");
        propertyValueName = stateValue.getMethod("valueName");
        propertyName = property.getMethod("getName");
    }

    /** Called from a client safe point after the matching state snapshot was captured. */
    public void onSafePoint(Object minecraft, boolean hasSubscribers) throws ReflectiveOperationException {
        if (!hasSubscribers) {
            tracker.clearContext();
            return;
        }
        Object client = resolveMinecraft(minecraft);
        if (client == null || !stateAccess.isClientThread(client)) return;
        long session = stateAccess.observeSessionGeneration(client);
        Object player = stateAccess.localPlayer(client);
        Object gui = minecraftGui.get(client);
        Object screen = gui == null ? null : guiScreen.invoke(gui);
        int slot = player == null ? -1 : stateAccess.selectedSlot(player);
        boolean onGround = player != null && stateAccess.isOnGround(player);
        boolean inWater = player != null && stateAccess.isInWater(player);
        tracker.seed(session, player, onGround, inWater, slot,
                screen == null ? "" : screen.getClass().getName());
    }

    /** Copies one mixin signal and queues only immutable values; core callbacks remain deferred. */
    public void capture(Object minecraft, ClientBehaviorSignal signal) throws ReflectiveOperationException {
        Object client = resolveMinecraft(minecraft);
        if (client == null || signal == null || !stateAccess.isClientThread(client)) return;
        long session = stateAccess.observeSessionGeneration(client);
        Object player = stateAccess.localPlayer(client);
        tracker.resetContext(session, player);
        ClientStateSnapshot.EntityIdentity previousIdentity = capturedPlayerIdentity;
        capturedPlayerIdentity = player == null ? null : stateAccess.observePlayerIdentity(client);
        try {
            switch (signal.kind()) {
                case ATTACK -> captureAttack(session, player, signal);
                case ITEM_USE_REQUEST, ITEM_USE_RESULT -> captureItemUse(session, player, signal, false);
                case ITEM_USE_ON_BLOCK_REQUEST, ITEM_USE_ON_BLOCK_RESULT -> captureItemUse(session, player, signal, true);
                case BREAK_START_REQUEST -> captureBreakStart(session, signal);
                case BREAK_START_NESTED_REQUEST -> captureNestedBreakStart(signal);
                case BREAK_START_RESULT -> captureBreakStartResult(session, signal);
                case BREAK_CONTINUE -> captureBreakContinue(session, signal);
                case BREAK_STOP -> captureBreakStop(session);
                case BREAK_PREDICTION -> captureBreakPrediction(session, signal);
                case DAMAGE_NOTIFICATION -> captureDamageNotification(session, player, signal.first());
                case HURT_ANIMATION -> captureHurtAnimation(session, player, signal.first());
                case HEALTH_UPDATE -> captureHealthUpdate(session, signal.first());
                case BLOCK_UPDATE -> captureBlockUpdate(session, signal.first());
                case SECTION_BLOCK_UPDATE -> captureSectionBlockUpdate(session, signal.first());
                case SERVER_HOTBAR_SLOT -> captureServerHotbar(session, player, client, signal.first());
                case JUMP -> captureJump(session, player, signal.source());
                case GROUND_STATE -> captureGround(session, player, signal.source());
                case FLUID_UPDATE_END -> captureWater(session, player, signal.source());
                case SCREEN_SET -> captureScreen(session, client);
                case FLUID_UPDATE_START -> { /* TAIL observation is authoritative. */ }
                case HOTBAR_SLOT_SET -> captureHotbar(session, player, client, signal.source(), signal.first());
            }
        } finally {
            capturedPlayerIdentity = previousIdentity;
        }
    }

    private Object resolveMinecraft(Object minecraft) throws ReflectiveOperationException {
        return minecraft == null ? getInstance.invoke(null) : minecraft;
    }

    private void captureAttack(long session, Object player, ClientBehaviorSignal signal)
            throws ReflectiveOperationException {
        if (signal.first() != player || player == null || signal.second() == null) return;
        Map<String, EventValue> fields = playerFields(player);
        put(fields, BehaviorEvent.Field.TARGET_ENTITY_ID, integer(stateAccess.entityId(signal.second())));
        emit(session, BehaviorEvent.Kind.ATTACK, BehaviorEvent.Phase.REQUESTED,
                BehaviorEvent.Source.LOCAL_INTENT, fields);
    }

    private void captureItemUse(long session, Object player, ClientBehaviorSignal signal, boolean onBlock)
            throws ReflectiveOperationException {
        if (signal.first() != player || player == null) return;
        boolean result = signal.kind() == ClientBehaviorSignal.Kind.ITEM_USE_RESULT
                || signal.kind() == ClientBehaviorSignal.Kind.ITEM_USE_ON_BLOCK_RESULT;
        Object hand = signal.second();
        Map<String, EventValue> fields = playerFields(player);
        put(fields, BehaviorEvent.Field.HAND, text(hand));
        putItem(fields, stateAccess.describeItem(stateAccess.itemInHand(player, hand)));
        if (onBlock) putBlockHit(fields, signal.third());
        if (result) put(fields, BehaviorEvent.Field.RESULT, text(signal.result()));
        emit(session, onBlock ? BehaviorEvent.Kind.ITEM_USE_ON_BLOCK : BehaviorEvent.Kind.ITEM_USE,
                result ? BehaviorEvent.Phase.LOCAL_RESULT : BehaviorEvent.Phase.REQUESTED,
                result ? BehaviorEvent.Source.CLIENT_PREDICTION : BehaviorEvent.Source.LOCAL_INTENT, fields);
    }

    private void captureBreakStart(long session, ClientBehaviorSignal signal) throws ReflectiveOperationException {
        ClientBehaviorStateTracker.BlockTarget target = target(signal.first(), signal.second());
        if (target == null) return;
        emitBreakChanges(session, tracker.startBreak(target, target.face()));
    }

    private void captureNestedBreakStart(ClientBehaviorSignal signal) throws ReflectiveOperationException {
        ClientBehaviorStateTracker.BlockTarget target = target(signal.first(), signal.second());
        if (target != null) tracker.startBreakNested(target, target.face());
    }

    private void captureBreakStartResult(long session, ClientBehaviorSignal signal) throws ReflectiveOperationException {
        ClientBehaviorStateTracker.BlockTarget target = target(signal.first(), signal.second());
        if (target == null) return;
        emitBreakChange(session, tracker.startResult(target, Boolean.TRUE.equals(signal.result())));
    }

    private void captureBreakContinue(long session, ClientBehaviorSignal signal) throws ReflectiveOperationException {
        ClientBehaviorStateTracker.BlockTarget target = target(signal.first(), signal.second());
        if (target != null) emitBreakChanges(session, tracker.continueBreak(target, target.face()));
    }

    private void captureBreakStop(long session) {
        ClientBehaviorStateTracker.BreakChange change = tracker.stopBreak();
        if (change != null) emitBreakChange(session, change);
    }

    private void captureBreakPrediction(long session, ClientBehaviorSignal signal) throws ReflectiveOperationException {
        ClientBehaviorStateTracker.BlockTarget target = target(signal.first(), null);
        if (target != null) emitBreakChange(session,
                tracker.predictedBreak(target, Boolean.TRUE.equals(signal.result())));
    }

    private void emitBreakChanges(long session, List<ClientBehaviorStateTracker.BreakChange> changes) {
        for (ClientBehaviorStateTracker.BreakChange change : changes) emitBreakChange(session, change);
    }

    private void emitBreakChange(long session, ClientBehaviorStateTracker.BreakChange change) {
        Map<String, EventValue> fields = positionFields(change.target());
        if (!change.target().face().isBlank()) put(fields, BehaviorEvent.Field.FACE, text(change.target().face()));
        if (change.reason() != null) put(fields, BehaviorEvent.Field.REASON, text(change.reason()));
        if (change.result() != null) put(fields, BehaviorEvent.Field.RESULT, flag(change.result()));
        BehaviorEvent.Phase phase = switch (change.behavior()) {
            case BREAK_REQUESTED -> BehaviorEvent.Phase.REQUESTED;
            case BREAK_CANCELLED -> BehaviorEvent.Phase.CANCEL_REQUESTED;
            case BREAK_RESULT -> BehaviorEvent.Phase.LOCAL_RESULT;
        };
        BehaviorEvent.Source source = phase == BehaviorEvent.Phase.LOCAL_RESULT
                ? BehaviorEvent.Source.CLIENT_PREDICTION : BehaviorEvent.Source.LOCAL_INTENT;
        emit(session, BehaviorEvent.Kind.BLOCK_BREAK, phase, source, fields);
    }

    private void captureDamageNotification(long session, Object player, Object packet)
            throws ReflectiveOperationException {
        if (packet == null || player == null
                || number(damageEntityId.invoke(packet)).intValue() != stateAccess.entityId(player)) return;
        Map<String, EventValue> fields = playerFields(player);
        put(fields, BehaviorEvent.Field.DAMAGE_TYPE_ID, text(resolveHolderId(damageSourceType.invoke(packet))));
        put(fields, BehaviorEvent.Field.SOURCE_ENTITY_ID, integer(number(damageCauseId.invoke(packet)).longValue()));
        put(fields, BehaviorEvent.Field.DIRECT_ENTITY_ID, integer(number(damageDirectId.invoke(packet)).longValue()));
        Object optionalValue = damageSourcePosition.invoke(packet);
        if (optionalValue instanceof Optional<?> optional && optional.isPresent()) {
            putVector(fields, optional.get());
        }
        emit(session, BehaviorEvent.Kind.DAMAGE_NOTIFICATION, BehaviorEvent.Phase.SERVER_NOTIFIED,
                BehaviorEvent.Source.SERVER_NOTIFICATION, fields);
    }

    private void captureHurtAnimation(long session, Object player, Object packet)
            throws ReflectiveOperationException {
        if (packet == null || player == null
                || number(hurtEntityId.invoke(packet)).intValue() != stateAccess.entityId(player)) return;
        Map<String, EventValue> fields = playerFields(player);
        put(fields, BehaviorEvent.Field.YAW, decimal(number(hurtYaw.invoke(packet)).doubleValue()));
        emit(session, BehaviorEvent.Kind.HURT_ANIMATION, BehaviorEvent.Phase.SERVER_NOTIFIED,
                BehaviorEvent.Source.SERVER_NOTIFICATION, fields);
    }

    private void captureHealthUpdate(long session, Object packet) throws ReflectiveOperationException {
        if (packet == null) return;
        Map<String, EventValue> fields = new LinkedHashMap<>();
        put(fields, BehaviorEvent.Field.HEALTH, decimal(number(healthValue.invoke(packet)).doubleValue()));
        emit(session, BehaviorEvent.Kind.HEALTH_UPDATE, BehaviorEvent.Phase.SERVER_NOTIFIED,
                BehaviorEvent.Source.SERVER_NOTIFICATION, fields);
    }

    private void captureBlockUpdate(long session, Object packet) throws ReflectiveOperationException {
        if (packet == null) return;
        Map<String, EventValue> fields = positionFields(target(blockPacketPos.invoke(packet), null));
        put(fields, BehaviorEvent.Field.BLOCK_STATE_ID,
                text(blockStateId(blockPacketState.invoke(packet))));
        emit(session, BehaviorEvent.Kind.BLOCK_UPDATE, BehaviorEvent.Phase.SERVER_NOTIFIED,
                BehaviorEvent.Source.SERVER_NOTIFICATION, fields);
    }

    private void captureSectionBlockUpdate(long session, Object packet) throws ReflectiveOperationException {
        if (packet == null) return;
        int[] count = {0};
        List<EventValue> changes = new ArrayList<>(MAX_SECTION_BLOCK_SAMPLES);
        sectionRunUpdates.invoke(packet, (BiConsumer<Object, Object>) (position, state) -> {
            int index = count[0]++;
            if (index >= MAX_SECTION_BLOCK_SAMPLES) return;
            try {
                Map<String, EventValue> item = positionFields(target(position, null));
                put(item, BehaviorEvent.Field.BLOCK_STATE_ID, text(blockStateId(state)));
                changes.add(new EventValue.ObjectValue(item));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Preserve the event count and bounded sample if one optional block description fails.
            }
        });
        Map<String, EventValue> fields = new LinkedHashMap<>();
        put(fields, BehaviorEvent.Field.CHANGE_COUNT, integer(count[0]));
        put(fields, BehaviorEvent.Field.CHANGES, new EventValue.Array(changes));
        put(fields, BehaviorEvent.Field.CHANGES_TRUNCATED, flag(count[0] > MAX_SECTION_BLOCK_SAMPLES));
        emit(session, BehaviorEvent.Kind.BLOCK_UPDATE, BehaviorEvent.Phase.SERVER_NOTIFIED,
                BehaviorEvent.Source.SERVER_NOTIFICATION, fields);
    }

    private void captureServerHotbar(long session, Object player, Object minecraft, Object packet)
            throws ReflectiveOperationException {
        if (player == null || packet == null) return;
        int slot = number(heldSlot.invoke(packet)).intValue();
        int actual = stateAccess.selectedSlot(player);
        if (slot != actual) return; // Invalid server slot packets are ignored by the game.
        ClientBehaviorStateTracker.SlotChange change = tracker.observeSlot(slot);
        Map<String, EventValue> fields = playerFields(player);
        if (change != null) put(fields, BehaviorEvent.Field.PREVIOUS_SLOT, integer(change.previousSlot()));
        put(fields, BehaviorEvent.Field.SLOT, integer(slot));
        put(fields, BehaviorEvent.Field.REASON, text("server-notification"));
        emit(session, BehaviorEvent.Kind.HOTBAR_SELECTION, BehaviorEvent.Phase.SERVER_NOTIFIED,
                BehaviorEvent.Source.SERVER_NOTIFICATION, fields);
    }

    private void captureJump(long session, Object player, Object entity) throws ReflectiveOperationException {
        if (player == null || entity != player) return;
        Map<String, EventValue> fields = playerFields(player);
        put(fields, BehaviorEvent.Field.ON_GROUND, flag(stateAccess.isOnGround(player)));
        emit(session, BehaviorEvent.Kind.JUMP, BehaviorEvent.Phase.REQUESTED,
                BehaviorEvent.Source.LOCAL_INTENT, fields);
    }

    private void captureGround(long session, Object player, Object entity) throws ReflectiveOperationException {
        if (player == null || entity != player) return;
        boolean onGround = stateAccess.isOnGround(player);
        Boolean previous = tracker.observeGround(onGround);
        if (previous == null || previous || !onGround) return;
        Map<String, EventValue> fields = playerFields(player);
        put(fields, BehaviorEvent.Field.PREVIOUS_ON_GROUND, flag(previous));
        put(fields, BehaviorEvent.Field.ON_GROUND, flag(onGround));
        emit(session, BehaviorEvent.Kind.LANDING, BehaviorEvent.Phase.OBSERVED_TRANSITION,
                BehaviorEvent.Source.CLIENT_OBSERVATION, fields);
    }

    private void captureWater(long session, Object player, Object entity) throws ReflectiveOperationException {
        if (player == null || entity != player) return;
        boolean inWater = stateAccess.isInWater(player);
        Boolean previous = tracker.observeWater(inWater);
        if (previous == null || previous == inWater) return;
        Map<String, EventValue> fields = playerFields(player);
        put(fields, BehaviorEvent.Field.PREVIOUS_IN_WATER, flag(previous));
        put(fields, BehaviorEvent.Field.IN_WATER, flag(inWater));
        emit(session, BehaviorEvent.Kind.WATER_TRANSITION, BehaviorEvent.Phase.OBSERVED_TRANSITION,
                BehaviorEvent.Source.CLIENT_OBSERVATION, fields);
    }

    private void captureHotbar(long session, Object player, Object minecraft, Object inventory, Object rawSlot)
            throws ReflectiveOperationException {
        if (player == null || inventory == null || !stateAccess.isLocalPlayerInventory(minecraft, inventory)) return;
        int slot = number(rawSlot).intValue();
        if (slot != stateAccess.selectedSlot(player)) return;
        ClientBehaviorStateTracker.SlotChange change = tracker.observeSlot(slot);
        if (change == null) return;
        Map<String, EventValue> fields = playerFields(player);
        put(fields, BehaviorEvent.Field.PREVIOUS_SLOT, integer(change.previousSlot()));
        put(fields, BehaviorEvent.Field.SLOT, integer(change.slot()));
        put(fields, BehaviorEvent.Field.REASON, text("local-selection"));
        emit(session, BehaviorEvent.Kind.HOTBAR_SELECTION, BehaviorEvent.Phase.OBSERVED_TRANSITION,
                BehaviorEvent.Source.CLIENT_OBSERVATION, fields);
    }

    private void captureScreen(long session, Object minecraft) throws ReflectiveOperationException {
        Object gui = minecraftGui.get(minecraft);
        Object screen = gui == null ? null : guiScreen.invoke(gui);
        String current = screen == null ? "" : screen.getClass().getName();
        ClientBehaviorStateTracker.ScreenChange change = tracker.observeScreen(current);
        if (change == null) return;
        Map<String, EventValue> fields = new LinkedHashMap<>();
        put(fields, BehaviorEvent.Field.PREVIOUS_SCREEN_TYPE, text(change.previousScreenType()));
        put(fields, BehaviorEvent.Field.SCREEN_TYPE, text(change.screenType()));
        emit(session, BehaviorEvent.Kind.SCREEN_CHANGE, BehaviorEvent.Phase.OBSERVED_TRANSITION,
                BehaviorEvent.Source.CLIENT_OBSERVATION, fields);
    }

    private void putBlockHit(Map<String, EventValue> fields, Object hit) throws ReflectiveOperationException {
        if (hit == null) return;
        putPosition(fields, hitBlockPos.invoke(hit));
        put(fields, BehaviorEvent.Field.FACE, text(hitDirection.invoke(hit)));
    }

    private ClientBehaviorStateTracker.BlockTarget target(Object position, Object face)
            throws ReflectiveOperationException {
        if (position == null) return null;
        int x = number(blockPosX.invoke(position)).intValue();
        int y = number(blockPosY.invoke(position)).intValue();
        int z = number(blockPosZ.invoke(position)).intValue();
        return new ClientBehaviorStateTracker.BlockTarget(x, y, z, face == null ? "" : String.valueOf(face));
    }

    private Map<String, EventValue> playerFields(Object player) throws ReflectiveOperationException {
        Map<String, EventValue> fields = new LinkedHashMap<>();
        put(fields, BehaviorEvent.Field.PLAYER_ENTITY_ID, integer(stateAccess.entityId(player)));
        return fields;
    }

    private Map<String, EventValue> positionFields(ClientBehaviorStateTracker.BlockTarget target) {
        Map<String, EventValue> fields = new LinkedHashMap<>();
        if (target != null) {
            put(fields, BehaviorEvent.Field.BLOCK_X, integer(target.x()));
            put(fields, BehaviorEvent.Field.BLOCK_Y, integer(target.y()));
            put(fields, BehaviorEvent.Field.BLOCK_Z, integer(target.z()));
        }
        return fields;
    }

    private void putPosition(Map<String, EventValue> fields, Object position) throws ReflectiveOperationException {
        put(fields, BehaviorEvent.Field.BLOCK_X, integer(number(blockPosX.invoke(position)).intValue()));
        put(fields, BehaviorEvent.Field.BLOCK_Y, integer(number(blockPosY.invoke(position)).intValue()));
        put(fields, BehaviorEvent.Field.BLOCK_Z, integer(number(blockPosZ.invoke(position)).intValue()));
    }

    private void putItem(Map<String, EventValue> fields, ClientStateSnapshot.ItemDescription item) {
        put(fields, BehaviorEvent.Field.ITEM_ID, text(item.itemId()));
        put(fields, BehaviorEvent.Field.ITEM_COUNT, integer(item.count()));
    }

    private void putVector(Map<String, EventValue> fields, Object vector) throws ReflectiveOperationException {
        put(fields, BehaviorEvent.Field.SOURCE_X, decimal(number(vecX.invoke(vector)).doubleValue()));
        put(fields, BehaviorEvent.Field.SOURCE_Y, decimal(number(vecY.invoke(vector)).doubleValue()));
        put(fields, BehaviorEvent.Field.SOURCE_Z, decimal(number(vecZ.invoke(vector)).doubleValue()));
    }

    private String resolveHolderId(Object holder) throws ReflectiveOperationException {
        Object named = holderRegisteredName.invoke(holder);
        if (named instanceof Optional<?> optional && optional.isPresent()) return String.valueOf(optional.get());
        return String.valueOf(holder);
    }

    private String blockStateId(Object state) throws ReflectiveOperationException {
        if (state == null) return "";
        Object block = blockStateBlock.invoke(state);
        Object registry = blockRegistry.get(null);
        String id = String.valueOf(registryKey.invoke(registry, block));
        Object rawValues = blockStateValues.invoke(state);
        if (!(rawValues instanceof java.util.stream.Stream<?> stream)) return id;
        List<String> properties = new ArrayList<>();
        try (stream) {
            stream.forEach(value -> {
                try {
                    Object property = propertyValueProperty.invoke(value);
                    String name = String.valueOf(propertyName.invoke(property));
                    String propertyValue = String.valueOf(propertyValueName.invoke(value));
                    properties.add(name + "=" + propertyValue);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Keep the stable namespaced block ID if one property is unavailable.
                }
            });
        }
        properties.sort(String::compareTo);
        return properties.isEmpty() ? id : id + "[" + String.join(",", properties) + "]";
    }

    private void emit(long session, BehaviorEvent.Kind kind, BehaviorEvent.Phase phase,
                      BehaviorEvent.Source source, Map<String, EventValue> fields) {
        Optional<ClientStateSnapshot.EntityIdentity> identity = switch (kind) {
            case BLOCK_UPDATE, SCREEN_CHANGE -> Optional.empty();
            default -> Optional.ofNullable(capturedPlayerIdentity);
        };
        emitCapturedBehaviorEvent(session, kind, phase, source, identity, fields);
    }

    /** Internal adapter-to-driver bridge, also exercised without launching a game in contract tests. */
    boolean emitCapturedBehaviorEvent(long session, BehaviorEvent.Kind kind, BehaviorEvent.Phase phase,
            BehaviorEvent.Source source, Optional<ClientStateSnapshot.EntityIdentity> playerIdentity,
            Map<String, EventValue> fields) {
        if (driver == null) return false;
        return driver.captureClientBehaviorEvent(session, kind, phase, source, playerIdentity,
                () -> new EventValue.ObjectValue(fields));
    }

    private static Number number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Minecraft numeric value unavailable");
        return number;
    }

    private static EventValue.Text text(Object value) {
        return new EventValue.Text(value == null ? "" : String.valueOf(value));
    }

    private static EventValue.IntegerNumber integer(long value) { return new EventValue.IntegerNumber(value); }
    private static EventValue.DecimalNumber decimal(double value) { return new EventValue.DecimalNumber(value); }
    private static EventValue.Flag flag(boolean value) { return new EventValue.Flag(value); }
    private static void put(Map<String, EventValue> fields, String key, EventValue value) {
        fields.put(key, value);
    }
}
