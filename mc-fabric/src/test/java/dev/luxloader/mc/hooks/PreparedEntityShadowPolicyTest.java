package dev.luxloader.mc.hooks;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedEntityShadowPolicyTest {
    @Test void hostShadowPreparationFollowsActivePipelineAndRestoresOnDetach() {
        var enabled = new AtomicBoolean(true);
        var host = (RenderHookHost) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RenderHookHost.class},
                (proxy, method, args) -> method.getName().equals("usesPreparedEntityShadows") ? enabled.get() : null);
        try {
            RenderHooks.install(null);
            assertTrue(RenderHooks.usesPreparedEntityShadows());
            RenderHooks.install(host);
            assertTrue(RenderHooks.usesPreparedEntityShadows());
            enabled.set(false);
            assertFalse(RenderHooks.usesPreparedEntityShadows());
            enabled.set(true);
            assertTrue(RenderHooks.usesPreparedEntityShadows());
        } finally { RenderHooks.install(null); }
        assertTrue(RenderHooks.usesPreparedEntityShadows());
    }
}
