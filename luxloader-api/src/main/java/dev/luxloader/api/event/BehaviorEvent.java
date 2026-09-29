package dev.luxloader.api.event;

import dev.luxloader.api.state.ClientStateSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * A built-in client behavior fact. Fields contain copied values only; a missing optional field is
 * unavailable. Player-scoped events carry the immutable local-player identity copied at their source.
 * It may differ from the batch snapshot if the player object was replaced before delivery. A local
 * request or prediction does not establish server acceptance.
 *
 * @param playerIdentity empty for global event kinds or when the local-player identity was unavailable
 *                       at capture time
 */
public record BehaviorEvent(
        EventStamp stamp,
        Kind kind,
        Phase phase,
        Source source,
        Optional<ClientStateSnapshot.EntityIdentity> playerIdentity,
        EventValue.ObjectValue fields) implements ClientEvent {

    public BehaviorEvent {
        stamp = Objects.requireNonNull(stamp, "stamp");
        kind = Objects.requireNonNull(kind, "kind");
        phase = Objects.requireNonNull(phase, "phase");
        source = Objects.requireNonNull(source, "source");
        playerIdentity = Objects.requireNonNull(playerIdentity, "playerIdentity");
        fields = Objects.requireNonNull(fields, "fields");
    }

    public enum Kind {
        /** A local game-mode attack call began; this is not a server confirmation. */
        ATTACK,
        /** A local item-use call and its client-side InteractionResult. */
        ITEM_USE,
        /** A local item-on-block call with hit position/face and its client-side result. */
        ITEM_USE_ON_BLOCK,
        /** The client applied the hurt animation packet; it need not correspond to a damage amount. */
        HURT_ANIMATION,
        /** A damage-event packet was applied to the local player; Minecraft 26.3 sends no damage amount here. */
        DAMAGE_NOTIFICATION,
        /** A health value from the server health packet, after client application. */
        HEALTH_UPDATE,
        /** The local player's jump method was entered before the impulse is applied. */
        JUMP,
        /** The local player's observed on-ground state changed from false to true. */
        LANDING,
        /** The local player's observed water state changed. */
        WATER_TRANSITION,
        /** Local block-break request, cancellation, result, or client prediction. */
        BLOCK_BREAK,
        /** A server block update was applied; section updates carry a bounded sample of changes. */
        BLOCK_UPDATE,
        /** The current GUI screen class changed. */
        SCREEN_CHANGE,
        /** A local hotbar selection transition or server-applied hotbar slot notification. */
        HOTBAR_SELECTION
    }

    public enum Phase {
        /** An operation was requested on the client. */
        REQUESTED,
        /** The game produced a local result or prediction, not a server acknowledgement. */
        LOCAL_RESULT,
        /** The client requested cancellation of an in-progress operation. */
        CANCEL_REQUESTED,
        /** A client state transition was observed after the corresponding game update. */
        OBSERVED_TRANSITION,
        /** A server-originated packet was applied on the client thread. */
        SERVER_NOTIFIED
    }

    /** The observation source; server notifications are client-applied packets, not direct server access. */
    public enum Source { LOCAL_INTENT, CLIENT_PREDICTION, CLIENT_OBSERVATION, SERVER_NOTIFICATION }

    /** Well-known immutable field names shared by behavior event kinds. */
    public static final class Field {
        private Field() { }
        /** Descriptive numeric ID; use {@link BehaviorEvent#playerIdentity()} for lifecycle-aware identity. */
        public static final String PLAYER_ENTITY_ID = "playerEntityId";
        /** Descriptive numeric target ID only; it is not a stable cross-lifecycle entity reference. */
        public static final String TARGET_ENTITY_ID = "targetEntityId";
        public static final String HAND = "hand";
        public static final String ITEM_ID = "itemId";
        public static final String ITEM_COUNT = "itemCount";
        public static final String RESULT = "result";
        public static final String BLOCK_X = "blockX";
        public static final String BLOCK_Y = "blockY";
        public static final String BLOCK_Z = "blockZ";
        public static final String FACE = "face";
        public static final String BLOCK_STATE_ID = "blockStateId";
        public static final String DAMAGE_TYPE_ID = "damageTypeId";
        public static final String SOURCE_ENTITY_ID = "sourceEntityId";
        public static final String DIRECT_ENTITY_ID = "directEntityId";
        public static final String SOURCE_X = "sourceX";
        public static final String SOURCE_Y = "sourceY";
        public static final String SOURCE_Z = "sourceZ";
        public static final String HEALTH = "health";
        public static final String YAW = "yaw";
        public static final String ON_GROUND = "onGround";
        public static final String IN_WATER = "inWater";
        public static final String SCREEN_TYPE = "screenType";
        public static final String PREVIOUS_SCREEN_TYPE = "previousScreenType";
        public static final String PREVIOUS_SLOT = "previousSlot";
        public static final String SLOT = "slot";
        public static final String REASON = "reason";
        public static final String PREVIOUS_ON_GROUND = "previousOnGround";
        public static final String PREVIOUS_IN_WATER = "previousInWater";
        public static final String CHANGE_COUNT = "changeCount";
        public static final String CHANGES = "changes";
        public static final String CHANGES_TRUNCATED = "changesTruncated";
    }
}
