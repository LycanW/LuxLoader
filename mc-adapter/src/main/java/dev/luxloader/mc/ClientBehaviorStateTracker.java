package dev.luxloader.mc;

import java.util.ArrayList;
import java.util.List;

/** Client-thread-only transition memory. Minecraft identities never leave this adapter. */
final class ClientBehaviorStateTracker {
    private long sessionGeneration = -1L;
    private Object playerIdentity;
    private Boolean onGround;
    private Boolean inWater;
    private Integer selectedSlot;
    private String screenType;
    private BlockTarget activeBreak;

    void resetContext(long sessionGeneration, Object playerIdentity) {
        if (this.sessionGeneration == sessionGeneration && this.playerIdentity == playerIdentity) return;
        this.sessionGeneration = sessionGeneration;
        this.playerIdentity = playerIdentity;
        clearValues();
    }

    void seed(long sessionGeneration, Object playerIdentity, boolean onGround,
              boolean inWater, int selectedSlot, String screenType) {
        resetContext(sessionGeneration, playerIdentity);
        this.onGround = onGround;
        this.inWater = inWater;
        this.selectedSlot = selectedSlot >= 0 ? selectedSlot : null;
        this.screenType = screenType == null ? "" : screenType;
    }

    void clearContext() {
        sessionGeneration = -1L;
        playerIdentity = null;
        clearValues();
    }

    Boolean observeGround(boolean value) {
        Boolean previous = onGround;
        onGround = value;
        return previous != null && previous != value ? previous : null;
    }

    Boolean observeWater(boolean value) {
        Boolean previous = inWater;
        inWater = value;
        return previous != null && previous != value ? previous : null;
    }

    SlotChange observeSlot(int value) {
        if (value < 0) return null;
        Integer previous = selectedSlot;
        selectedSlot = value;
        return previous != null && previous != value ? new SlotChange(previous, value) : null;
    }

    ScreenChange observeScreen(String value) {
        String current = value == null ? "" : value;
        String previous = screenType;
        screenType = current;
        return previous != null && !previous.equals(current) ? new ScreenChange(previous, current) : null;
    }

    List<BreakChange> startBreak(BlockTarget target, String face) {
        List<BreakChange> changes = new ArrayList<>(2);
        switchTargetIfNeeded(target, face, "target-switch", changes);
        activeBreak = target.withFace(face);
        changes.add(new BreakChange(Behavior.BREAK_REQUESTED, activeBreak, "start", null));
        return List.copyOf(changes);
    }

    /** Record the vanilla startDestroyBlock call nested inside continueDestroyBlock without a second request. */
    List<BreakChange> startBreakNested(BlockTarget target, String face) {
        if (target == null) return List.of();
        activeBreak = target.withFace(face);
        return List.of();
    }

    List<BreakChange> continueBreak(BlockTarget target, String face) {
        if (activeBreak != null && activeBreak.samePosition(target)) return List.of();
        List<BreakChange> changes = new ArrayList<>(2);
        switchTargetIfNeeded(target, face, activeBreak == null ? "continue" : "target-switch", changes);
        activeBreak = target.withFace(face);
        changes.add(new BreakChange(Behavior.BREAK_REQUESTED, activeBreak, "continue", null));
        return List.copyOf(changes);
    }

    BreakChange stopBreak() {
        BlockTarget target = activeBreak;
        activeBreak = null;
        return target == null ? null : new BreakChange(Behavior.BREAK_CANCELLED, target, "stop", null);
    }

    BreakChange startResult(BlockTarget target, boolean accepted) {
        if (!accepted && activeBreak != null && activeBreak.samePosition(target)) activeBreak = null;
        return new BreakChange(Behavior.BREAK_RESULT, target, "start-result", accepted);
    }

    BreakChange predictedBreak(BlockTarget target, boolean destroyed) {
        if (destroyed && activeBreak != null && activeBreak.samePosition(target)) activeBreak = null;
        return new BreakChange(Behavior.BREAK_RESULT, target, "local-prediction", destroyed);
    }

    private void switchTargetIfNeeded(BlockTarget target, String face, String reason,
                                      List<BreakChange> changes) {
        if (activeBreak != null && !activeBreak.samePosition(target)) {
            changes.add(new BreakChange(Behavior.BREAK_CANCELLED, activeBreak,
                    reason, null));
        }
    }

    private void clearValues() {
        onGround = null;
        inWater = null;
        selectedSlot = null;
        screenType = null;
        activeBreak = null;
    }

    enum Behavior { BREAK_REQUESTED, BREAK_CANCELLED, BREAK_RESULT }

    record SlotChange(int previousSlot, int slot) { }
    record ScreenChange(String previousScreenType, String screenType) { }
    record BlockTarget(int x, int y, int z, String face) {
        BlockTarget withFace(String face) { return new BlockTarget(x, y, z, face == null ? "" : face); }
        boolean samePosition(BlockTarget other) {
            return other != null && x == other.x && y == other.y && z == other.z;
        }
    }
    record BreakChange(Behavior behavior, BlockTarget target, String reason, Boolean result) { }
}
