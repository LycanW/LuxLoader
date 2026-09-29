package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.LuxMod;
import dev.luxloader.api.event.ClientEventService;
import dev.luxloader.api.state.ClientStateService;
import dev.luxloader.api.resource.ResourcePreparationService;
import dev.luxloader.core.capability.CapabilityRegistryImpl;
import dev.luxloader.core.diag.DiagnosticsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResourceOwnerCleanupTest {
    @TempDir Path output;
    @Test void driverWorldSafePointAndPipelineTeardownCancelRealScopesWithoutStateSubscribers() throws Exception {
        try(var driver=new RenderDriverImpl(output)) {
            driver.initialize(dev.luxloader.api.plugin.RenderDriver.DeviceRequest.attachedToGame());
            var scope=driver.resourcePreparation(42).openScope();
            var old=ResourcePreparationHubTest.terminal(scope.submit(List.of(),input->1));
            driver.observeClientStateSafePoint(false,2,(generation,time)->{fail("No snapshot demand");return null;});
            assertTrue(old.result().isEmpty());assertEquals(2,scope.generation().world());
            var replacement=ResourcePreparationHubTest.terminal(scope.submit(List.of(),input->2));
            var teardown=RenderDriverImpl.class.getDeclaredMethod("teardownActivePipeline");teardown.setAccessible(true);teardown.invoke(driver);
            assertTrue(scope.isClosed());assertTrue(replacement.result().isEmpty());
            assertEquals(ResourcePreparationService.Failure.CLOSED,scope.submit(List.of(),input->3).failure());
        }
    }
    @Test void resourceCleanupFailureCannotSkipStateAndEventCleanupOrKeepOldServicesActive() {
        for (boolean linkage : new boolean[]{false, true}) {
            var calls = new ArrayList<String>();
            var runtime = (HostServicesImpl.RuntimeContext)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{HostServicesImpl.RuntimeContext.class}, (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "clientState" -> ClientStateService.EMPTY;
                            case "clientEvents" -> ClientEventService.EMPTY;
                            case "resourcePreparation" -> ResourcePreparationService.UNAVAILABLE;
                            case "releaseResourceOwner" -> {
                                calls.add("resources");
                                if (linkage) throw new LinkageError("Broken resource cleanup");
                                throw new IllegalStateException("Broken resource cleanup");
                            }
                            case "releaseClientStateOwner" -> { calls.add("state"); yield null; }
                            case "releaseClientEventsOwner" -> { calls.add("events"); yield null; }
                            default -> throw new AssertionError(method.getName());
                        };
                    });
            var diagnostics = new DiagnosticsImpl(output, "cleanup", false);
            var services = new HostServicesImpl(LuxMod.builder(new GpuId("test", "owner"), "owner", "1").build(),
                    new CapabilityRegistryImpl(), diagnostics, null, runtime);
            var retained = services.resourcePreparation();
            assertDoesNotThrow(services::deactivate);
            assertEquals(List.of("resources", "state", "events"), calls);
            assertFalse(services.isActive());
            assertThrows(IllegalStateException.class, retained::openScope);
            services.deactivate(); assertEquals(3, calls.size());
            assertTrue(diagnostics.exportReport().contains("Broken resource cleanup"));
        }
    }
}
