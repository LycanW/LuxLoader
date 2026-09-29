package dev.luxloader.mc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientBehaviorStateTrackerTest {
    @Test
    @DisplayName("Ground, water, slot, and screen observations emit transitions only")
    void ordinaryTransitionsIgnoreRepeatedValues() {
        ClientBehaviorStateTracker tracker = new ClientBehaviorStateTracker();
        Object player = new Object();
        tracker.seed(1L, player, false, false, 0, "menu");

        assertEquals(Boolean.FALSE, tracker.observeGround(true));
        assertNull(tracker.observeGround(true));
        assertEquals(Boolean.TRUE, tracker.observeGround(false));
        assertNull(tracker.observeWater(false));
        assertEquals(Boolean.FALSE, tracker.observeWater(true));
        assertNull(tracker.observeWater(true));
        assertNull(tracker.observeSlot(0));
        assertEquals(new ClientBehaviorStateTracker.SlotChange(0, 2), tracker.observeSlot(2));
        assertNull(tracker.observeScreen("menu"));
        assertEquals(new ClientBehaviorStateTracker.ScreenChange("menu", "world"),
                tracker.observeScreen("world"));
    }

    @Test
    @DisplayName("Break target switches cancel the old target and stop clears the active target")
    void breakTargetSwitchAndStop() {
        ClientBehaviorStateTracker tracker = new ClientBehaviorStateTracker();
        Object player = new Object();
        tracker.seed(4L, player, true, false, 0, "world");
        var first = new ClientBehaviorStateTracker.BlockTarget(1, 2, 3, "NORTH");
        var second = new ClientBehaviorStateTracker.BlockTarget(4, 5, 6, "UP");

        assertEquals(1, tracker.startBreak(first, first.face()).size());
        var switched = tracker.continueBreak(second, second.face());
        assertEquals(2, switched.size());
        assertEquals(ClientBehaviorStateTracker.Behavior.BREAK_CANCELLED,
                switched.getFirst().behavior());
        assertEquals(first, switched.getFirst().target());
        assertEquals("target-switch", switched.getFirst().reason());
        assertEquals(ClientBehaviorStateTracker.Behavior.BREAK_REQUESTED,
                switched.getLast().behavior());
        assertEquals(second, switched.getLast().target());
        assertEquals(ClientBehaviorStateTracker.Behavior.BREAK_CANCELLED,
                tracker.stopBreak().behavior());
        assertNull(tracker.stopBreak());
    }

    @Test
    @DisplayName("Continue target switch followed by nested start publishes one request")
    void nestedStartAfterContinueSwitchDoesNotDuplicateRequest() throws ReflectiveOperationException {
        ClientBehaviorStateTracker tracker = new ClientBehaviorStateTracker();
        Object player = new Object();
        tracker.seed(4L, player, true, false, 0, "world");
        var first = new ClientBehaviorStateTracker.BlockTarget(1, 2, 3, "NORTH");
        var second = new ClientBehaviorStateTracker.BlockTarget(4, 5, 6, "UP");

        assertEquals(1, tracker.startBreak(first, first.face()).size());
        var switched = tracker.continueBreak(second, second.face());
        var nestedStart = nestedStart(tracker, second);

        assertEquals(1, switched.stream().filter(change -> change.behavior()
                == ClientBehaviorStateTracker.Behavior.BREAK_CANCELLED).count());
        assertEquals(1, java.util.stream.Stream.concat(switched.stream(), nestedStart.stream())
                .filter(change -> change.behavior() == ClientBehaviorStateTracker.Behavior.BREAK_REQUESTED)
                .count(), "continue plus its nested start must describe one new request");
        assertEquals(first, switched.getFirst().target());
        assertEquals(second, switched.getLast().target());

        // The nested marker is one-shot: a later independent start at the same coordinate is real.
        assertEquals(1, tracker.startBreak(second, second.face()).stream()
                .filter(change -> change.behavior() == ClientBehaviorStateTracker.Behavior.BREAK_REQUESTED).count());
        tracker.startResult(second, false);
        assertEquals(1, tracker.startBreak(second, second.face()).stream()
                .filter(change -> change.behavior() == ClientBehaviorStateTracker.Behavior.BREAK_REQUESTED).count(),
                "a failed start may be retried independently");
        assertEquals(ClientBehaviorStateTracker.Behavior.BREAK_CANCELLED, tracker.stopBreak().behavior());
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<ClientBehaviorStateTracker.BreakChange> nestedStart(
            ClientBehaviorStateTracker tracker, ClientBehaviorStateTracker.BlockTarget target)
            throws ReflectiveOperationException {
        try {
            var method = ClientBehaviorStateTracker.class.getDeclaredMethod("startBreakNested",
                    ClientBehaviorStateTracker.BlockTarget.class, String.class);
            method.setAccessible(true);
            return (java.util.List<ClientBehaviorStateTracker.BreakChange>) method.invoke(
                    tracker, target, target.face());
        } catch (NoSuchMethodException notFixedYet) {
            // Red behavior before the fix: model the existing unconditional start request.
            return tracker.startBreak(target, target.face());
        }
    }

    @Test
    @DisplayName("A world or local-player object replacement clears all transition memory")
    void contextReplacementClearsBreakAndGroundState() {
        ClientBehaviorStateTracker tracker = new ClientBehaviorStateTracker();
        Object firstPlayer = new Object();
        tracker.seed(1L, firstPlayer, true, true, 3, "world");
        tracker.startBreak(new ClientBehaviorStateTracker.BlockTarget(1, 1, 1, "UP"), "UP");

        Object replacementPlayer = new Object();
        tracker.resetContext(2L, replacementPlayer);
        assertNull(tracker.stopBreak());
        assertNull(tracker.observeGround(false));
        assertNull(tracker.observeWater(false));
        assertNull(tracker.observeSlot(0));
        assertNull(tracker.observeScreen("menu"));

        // A subsequent safe-point seed establishes the fresh baseline.
        tracker.seed(2L, replacementPlayer, false, false, 0, "menu");
        assertTrue(tracker.observeGround(true) != null);
        assertFalse(tracker.observeWater(false) != null);
    }
}
