package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.event.ClientEventService;
import dev.luxloader.api.presentation.PresentationService;
import dev.luxloader.api.state.ClientStateService;
import dev.luxloader.core.capability.CapabilityRegistryImpl;
import dev.luxloader.core.diag.DiagnosticsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PresentationOwnerCleanupTest {
    @TempDir Path output;
    @Test void presentationCleanupFailureCannotSkipOtherOwnersOrReviveOldServices() {
        for (boolean linkage : new boolean[]{false, true}) {
            var calls = new ArrayList<String>();
            var runtime = (HostServicesImpl.RuntimeContext)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HostServicesImpl.RuntimeContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "clientState" -> ClientStateService.EMPTY;
                        case "clientEvents" -> ClientEventService.EMPTY;
                        case "presentations" -> PresentationService.UNAVAILABLE;
                        case "releaseResourceOwner" -> { calls.add("resources"); yield null; }
                        case "releasePresentationOwner" -> {
                            calls.add("presentations");
                            if (linkage) throw new LinkageError("Broken presentation cleanup");
                            throw new IllegalStateException("Broken presentation cleanup");
                        }
                        case "releaseClientStateOwner" -> { calls.add("state"); yield null; }
                        case "releaseClientEventsOwner" -> { calls.add("events"); yield null; }
                        default -> throw new AssertionError(method.getName());
                    });
            var diagnostics = new DiagnosticsImpl(output, "cleanup", false);
            var services = new HostServicesImpl(LuxMod.builder(new GpuId("test", "owner"), "owner", "1").build(),
                    new CapabilityRegistryImpl(), diagnostics, null, runtime);
            var retained = services.presentations();
            assertDoesNotThrow(services::deactivate); assertFalse(services.isActive());
            assertEquals(List.of("resources", "presentations", "state", "events"), calls);
            assertEquals(PresentationService.Capabilities.UNAVAILABLE, retained.capabilities());
            assertThrows(IllegalStateException.class, () -> retained.observeHostSounds(batch -> { }));
            services.deactivate(); assertEquals(4, calls.size());
            assertTrue(diagnostics.exportReport().contains("Broken presentation cleanup"));
        }
    }

    @Test void anInFlightRegistrationOrSubscriptionCannotSurviveDeactivation() throws Exception {
        for (boolean observe : new boolean[]{false, true}) {
            try (var hub = new PresentationHub(error -> fail(error)); var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                var entered = new java.util.concurrent.CountDownLatch(1); var released = new java.util.concurrent.CountDownLatch(1);
                var runtime = (HostServicesImpl.RuntimeContext)Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{HostServicesImpl.RuntimeContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
                            case "clientState" -> ClientStateService.EMPTY;
                            case "clientEvents" -> ClientEventService.EMPTY;
                            case "presentations" -> {
                                entered.countDown(); assertTrue(released.await(2, java.util.concurrent.TimeUnit.SECONDS));
                                yield hub.service((long)arguments[0], (String)arguments[1]);
                            }
                            case "releasePresentationOwner" -> { hub.releaseOwner((long)arguments[0]); released.countDown(); yield null; }
                            case "releaseResourceOwner", "releaseClientStateOwner", "releaseClientEventsOwner" -> null;
                            default -> throw new AssertionError(method.getName());
                        });
                var diagnostics = new DiagnosticsImpl(output, "cleanup", false);
                var services = new HostServicesImpl(LuxMod.builder(PresentationHubTest.OWNER, "owner", "1").build(),
                        new CapabilityRegistryImpl(), diagnostics, null, runtime);
                var retained = services.presentations();
                var request = worker.submit(() -> assertThrows(IllegalStateException.class, () -> {
                    if (observe) retained.observeHostSounds(batch -> fail("Unloaded listener"));
                    else retained.register(new PresentationService.Definition(PresentationHubTest.TYPE, null,
                            PresentationHubTest.SOUND, PresentationService.Category.PLAYERS, false));
                }));
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); services.deactivate();
                request.get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(0, hub.metrics().registrations()); assertEquals(0, hub.metrics().observers()); assertFalse(hub.needsState());
            }
        }
    }
}
