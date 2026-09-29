package dev.luxloader.mc;

import dev.luxloader.api.state.ClientStateSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIdentityTrackerTest {
    @Test
    @DisplayName("Same-dimension reconnect creates a new world session")
    void sameDimensionReconnectCreatesNewSession() {
        ClientIdentityTracker tracker = new ClientIdentityTracker();
        Object firstLevel = new Object();
        Object firstConnection = new Object();
        Object firstPlayer = new Object();
        var first = tracker.observe(firstLevel, firstConnection, "minecraft:overworld", firstPlayer);

        tracker.observe(null, null, "", null);
        var rejoined = tracker.observe(new Object(), new Object(), "minecraft:overworld", new Object());

        assertEquals("minecraft:overworld", rejoined.dimensionId());
        assertTrue(rejoined.worldLoaded());
        assertTrue(rejoined.sessionGeneration() > first.sessionGeneration());
    }

    @Test
    @DisplayName("Respawn with a reused entity ID and UUID receives a new object generation")
    void respawnWithReusedEntityIdChangesIdentity() {
        ClientIdentityTracker tracker = new ClientIdentityTracker();
        Object level = new Object();
        Object connection = new Object();
        UUID playerUuid = UUID.fromString("b79b7aa1-6ad0-4052-b769-355def3fe3bc");
        Object beforeObject = new Object();
        var before = tracker.observe(level, connection, "minecraft:overworld", beforeObject);
        Object afterObject = new Object();
        var after = tracker.observe(level, connection, "minecraft:overworld", afterObject);

        var beforeIdentity = new ClientStateSnapshot.EntityIdentity(before.sessionGeneration(), 7,
                playerUuid.toString(), before.playerGeneration());
        var afterIdentity = new ClientStateSnapshot.EntityIdentity(after.sessionGeneration(), 7,
                playerUuid.toString(), after.playerGeneration());
        assertEquals(before.sessionGeneration(), after.sessionGeneration());
        assertNotEquals(beforeIdentity, afterIdentity);
        assertTrue(after.playerGeneration() > before.playerGeneration());
    }

    @Test
    @DisplayName("A dimension change creates a new session even if the host level reference is reused")
    void dimensionChangeCreatesNewSession() {
        ClientIdentityTracker tracker = new ClientIdentityTracker();
        Object level = new Object();
        Object connection = new Object();
        var first = tracker.observe(level, connection, "minecraft:overworld", new Object());
        var changed = tracker.observe(level, connection, "minecraft:the_nether", new Object());

        assertNotEquals(first.sessionGeneration(), changed.sessionGeneration());
        assertEquals("minecraft:the_nether", changed.dimensionId());
    }
}
