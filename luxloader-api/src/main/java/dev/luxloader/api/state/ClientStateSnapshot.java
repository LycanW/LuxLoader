package dev.luxloader.api.state;

import java.util.Objects;

/**
 * Game-independent copy of the environment and local-player facts observed at one client update
 * boundary. No Minecraft object or mutable collection escapes through this value.
 */
public record ClientStateSnapshot(
        long sampleSequence,
        LogicalTime time,
        WorldSession world,
        Environment environment,
        Player player) {

    public ClientStateSnapshot {
        if (sampleSequence < 0) throw new IllegalArgumentException("sampleSequence must not be negative");
        time = Objects.requireNonNull(time, "time");
        world = Objects.requireNonNull(world, "world");
        environment = Objects.requireNonNull(environment, "environment");
        player = Objects.requireNonNull(player, "player");
    }

    /** Host logic time is independent of day/night time, sample sequence, and render-frame time. */
    public record LogicalTime(long tick, long elapsedNanos, boolean paused) {
        public LogicalTime {
            if (tick < 0 || elapsedNanos < 0) throw new IllegalArgumentException("logical time must not be negative");
        }
    }

    /** One loaded-world lifetime. Generation changes even when a later session uses the same dimension. */
    public record WorldSession(long generation, boolean loaded, String dimensionId) {
        public WorldSession {
            if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
            dimensionId = dimensionId == null ? "" : dimensionId;
        }
    }

    /** Existing environment time/weather semantics plus a point-in-world biome observation. */
    public record Environment(
            boolean valid,
            String dimensionId,
            long dayTimeTicks,
            float rainStrength,
            float thunderStrength,
            BiomeSample biome) {
        public static final Environment UNAVAILABLE = new Environment(false, "", 0L, 0f, 0f,
                BiomeSample.UNAVAILABLE);

        public Environment {
            dimensionId = dimensionId == null ? "" : dimensionId;
            biome = biome == null ? BiomeSample.UNAVAILABLE : biome;
            if (!Float.isFinite(rainStrength) || !Float.isFinite(thunderStrength)) {
                throw new IllegalArgumentException("weather strengths must be finite");
            }
        }
    }

    /** Biome sampled at the local player's block position, not by scanning the world. */
    public record BiomeSample(boolean valid, String biomeId, int blockX, int blockY, int blockZ) {
        public static final BiomeSample UNAVAILABLE = new BiomeSample(false, "", 0, 0, 0);

        public BiomeSample {
            biomeId = biomeId == null ? "" : biomeId;
            if (valid && biomeId.isBlank()) throw new IllegalArgumentException("valid biome sample requires an ID");
        }
    }

    /** Identity is scoped to a world session and player-object lifetime; entity ID and UUID remain descriptive. */
    public record EntityIdentity(long sessionGeneration, int entityId, String uuid, long generation) {
        public EntityIdentity {
            if (sessionGeneration < 0 || generation < 0) {
                throw new IllegalArgumentException("identity generations must not be negative");
            }
            uuid = uuid == null ? "" : uuid;
        }
    }

    /** Local-player state. Position and velocity are world-space values copied at the update boundary. */
    public record Player(
            boolean valid,
            EntityIdentity identity,
            double x, double y, double z,
            float yaw, float pitch,
            double velocityX, double velocityY, double velocityZ,
            boolean onGround,
            boolean crouching,
            boolean sprinting,
            boolean swimming,
            boolean underwater,
            Pose pose,
            boolean healthValid,
            float health,
            float maxHealth,
            boolean inventoryValid,
            int selectedSlot,
            ItemDescription mainHand,
            ItemDescription offHand) {
        public static final Player UNAVAILABLE = new Player(false, null,
                0d, 0d, 0d, 0f, 0f, 0d, 0d, 0d,
                false, false, false, false, false, Pose.UNKNOWN,
                false, 0f, 0f, false, -1, ItemDescription.EMPTY, ItemDescription.EMPTY);

        public Player {
            if (valid && identity == null) throw new IllegalArgumentException("valid player requires an identity");
            pose = pose == null ? Pose.UNKNOWN : pose;
            mainHand = mainHand == null ? ItemDescription.EMPTY : mainHand;
            offHand = offHand == null ? ItemDescription.EMPTY : offHand;
            if (healthValid && (!Float.isFinite(health) || !Float.isFinite(maxHealth))) {
                throw new IllegalArgumentException("health values must be finite");
            }
        }
    }

    /** Small item description. Item NBT and arbitrary component payloads are intentionally excluded. */
    public record ItemDescription(String itemId, int count, int damage, int maxDamage) {
        public static final ItemDescription EMPTY = new ItemDescription("", 0, 0, 0);

        public ItemDescription {
            itemId = itemId == null ? "" : itemId;
            if (count < 0 || damage < 0 || maxDamage < 0) {
                throw new IllegalArgumentException("item counts and damage must not be negative");
            }
        }
    }

    public enum Pose {
        STANDING, FALL_FLYING, SLEEPING, SWIMMING, SPIN_ATTACK, CROUCHING, DYING, UNKNOWN
    }
}
