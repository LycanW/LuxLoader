package dev.luxloader.mc.hooks;

/** Central demand gate so idle mixins do not allocate event signal or payload objects. */
public final class ClientBehaviorHooks {
    private ClientBehaviorHooks() { }

    public static void capture(ClientBehaviorSignal.Kind kind, Object source, Object first,
                               Object second, Object third, Object result) {
        if (!RenderHooks.wantsClientBehaviorCapture()) return;
        if (kind == ClientBehaviorSignal.Kind.HOTBAR_SLOT_SET
                && RenderHooks.isApplyingServerHotbarSlotNotification()) return;
        RenderHooks.onClientBehaviorSignal(null,
                new ClientBehaviorSignal(kind, source, first, second, third, result));
    }
}
