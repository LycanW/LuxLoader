package dev.luxloader.mc.hooks;

/** Internal data copied at a Minecraft method entrance and consumed by the adapter before the safe point. */
public record ClientBehaviorSignal(Kind kind, Object source, Object first, Object second,
                                   Object third, Object result) {
    public ClientBehaviorSignal {
        if (kind == null) throw new NullPointerException("kind");
    }

    public enum Kind {
        ATTACK,
        ITEM_USE_REQUEST,
        ITEM_USE_RESULT,
        ITEM_USE_ON_BLOCK_REQUEST,
        ITEM_USE_ON_BLOCK_RESULT,
        BREAK_START_REQUEST,
        BREAK_START_NESTED_REQUEST,
        BREAK_START_RESULT,
        BREAK_CONTINUE,
        BREAK_STOP,
        BREAK_PREDICTION,
        DAMAGE_NOTIFICATION,
        HURT_ANIMATION,
        HEALTH_UPDATE,
        BLOCK_UPDATE,
        SECTION_BLOCK_UPDATE,
        SERVER_HOTBAR_SLOT,
        JUMP,
        GROUND_STATE,
        FLUID_UPDATE_START,
        FLUID_UPDATE_END,
        SCREEN_SET,
        HOTBAR_SLOT_SET
    }
}
