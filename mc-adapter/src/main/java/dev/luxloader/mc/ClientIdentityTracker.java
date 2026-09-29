package dev.luxloader.mc;

/** Tracks opaque host object lifetimes without exporting or retaining game objects in the API. */
final class ClientIdentityTracker {
    private Object lastLevel;
    private Object lastConnection;
    private Object lastPlayer;
    private String lastDimension = "";
    private long sessionGeneration;
    private long playerGeneration;
    private boolean initialized;

    Lifecycle observe(Object level, Object connection, String dimensionId, Object player) {
        String dimension = dimensionId == null ? "" : dimensionId;
        boolean loaded = level != null;
        if (!initialized) {
            sessionGeneration = loaded ? 1L : 0L;
            initialized = true;
        } else if (level != lastLevel || connection != lastConnection || !dimension.equals(lastDimension)) {
            sessionGeneration = increment(sessionGeneration);
        }
        if (player != lastPlayer && player != null) {
            playerGeneration = increment(playerGeneration);
        }

        boolean changed = level != lastLevel || connection != lastConnection
                || player != lastPlayer || !dimension.equals(lastDimension);
        lastLevel = level;
        lastConnection = connection;
        lastPlayer = player;
        lastDimension = dimension;
        return new Lifecycle(sessionGeneration, loaded, dimension, playerGeneration, player, changed);
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    record Lifecycle(long sessionGeneration, boolean worldLoaded, String dimensionId,
                     long playerGeneration, Object currentPlayer, boolean changed) { }
}
