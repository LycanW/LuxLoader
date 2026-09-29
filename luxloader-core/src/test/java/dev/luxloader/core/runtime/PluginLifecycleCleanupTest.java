package dev.luxloader.core.runtime;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.api.scene.CompiledSceneMesh;
import dev.luxloader.api.scene.DynamicSceneMesh;
import dev.luxloader.api.scene.SceneImage;
import dev.luxloader.api.scene.SceneSnapshot;
import dev.luxloader.api.state.ClientStateSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Lifecycle and scene-contribution ownership tests without a GPU. They pin the cleanup contract:
 * reload and close unload every replaced plugin instance exactly once, one plugin's unload failure
 * does not stop the others, a pipeline switch never unloads plugins, and scene contributions belong
 * to the registering plugin.
 */
class PluginLifecycleCleanupTest {

    @TempDir
    Path configDir;

    private RenderDriverImpl driver;

    @BeforeEach
    void setUp() {
        RecordingLifecyclePlugin.reset();
        FakeTestPlugin.reset();
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
        RecordingLifecyclePlugin.reset();
        FakeTestPlugin.reset();
    }

    /** Driver whose device is supplied later by the host, so lifecycle paths need no Vulkan. */
    private void initializeDriver() {
        driver = new RenderDriverImpl(configDir);
        driver.initialize(RenderDriver.DeviceRequest.attachedToGame());
    }

    /**
     * Plugin IDs used by the recording plugin, in discovery order. Each initialization or reload
     * creates one new instance with a fresh ID.
     */
    private List<String> recordingPluginIds() {
        String prefix = RecordingLifecyclePlugin.ID + "/";
        return driver.loadedPlugins().stream().filter(id -> id.startsWith(prefix)).sorted().toList();
    }

    private RecordingLifecyclePlugin instance(String pluginId) {
        RecordingLifecyclePlugin found = RecordingLifecyclePlugin.instances().get(pluginId);
        assertTrue(found != null, "No recording plugin instance for " + pluginId);
        return found;
    }

    @Test
    @DisplayName("Reload Unloads Replaced Instances Exactly Once")
    void reloadUnloadsReplacedInstancesExactlyOnce() {
        initializeDriver();
        List<String> before = recordingPluginIds();
        assertEquals(1, before.size(), "One recording plugin instance must load: " + before);
        String firstId = before.get(0);

        driver.reload("生命周期行为测试");

        List<String> after = recordingPluginIds();
        assertEquals(1, after.size(), "Reload must load one new instance: " + after);
        String secondId = after.get(0);

        assertNotSame(firstId, secondId, "Reload must not silently reuse the previous plugin object");
        RecordingLifecyclePlugin first = instance(firstId);
        RecordingLifecyclePlugin second = instance(secondId);
        assertTrue(first.loaded() && second.loaded(), "Both instances must have completed onLoad");
        assertTrue(first.unloaded(),
                "Reload must unload the replaced plugin instance " + firstId);
        assertEquals(1, first.unloadCount(), "The replaced instance must unload exactly once");
        assertFalse(second.unloaded(),
                "Reload must not unload the instance it just loaded (" + secondId + ")");
        assertEquals(1, second.loadCount(), "The new instance must load exactly once");

        driver.close();
        assertTrue(second.unloaded(), "Close must unload the instance loaded by reload");
        assertEquals(1, second.unloadCount(), "The instance loaded by reload must unload exactly once");
        driver = null;
    }

    @Test
    @DisplayName("Close Unloads Each Instance Once")
    void closeUnloadsEachInstanceOnce() {
        initializeDriver();
        String id = recordingPluginIds().get(0);
        RecordingLifecyclePlugin plugin = instance(id);
        assertFalse(plugin.unloaded(), "A live plugin instance must not be unloaded");

        driver.close();
        assertTrue(plugin.unloaded(), "Close must unload the loaded plugin instance");
        assertEquals(1, plugin.unloadCount(), "Close must unload once");

        driver.close();
        assertEquals(1, plugin.unloadCount(), "Repeated close must not unload a second time");
        driver = null;
    }

    @Test
    @DisplayName("Reload Cancels Only The Replaced Client State Owner")
    void reloadCancelsOnlyTheReplacedClientStateOwner() {
        RecordingLifecyclePlugin.subscribeToClientStateOnLoad = true;
        initializeDriver();

        String oldId = recordingPluginIds().get(0);
        RecordingLifecyclePlugin oldPlugin = instance(oldId);
        var oldSubscription = oldPlugin.clientStateSubscription();
        assertTrue(oldSubscription != null && !oldSubscription.isCancelled());
        driver.observeClientStateSafePoint(false, 1L, PluginLifecycleCleanupTest::stateSample);
        assertEquals(1, oldPlugin.clientStateBatches().size());
        assertTrue(oldPlugin.clientStateBatches().getFirst().initial());

        driver.reload("client state owner reload");

        String newId = recordingPluginIds().get(0);
        RecordingLifecyclePlugin newPlugin = instance(newId);
        var newSubscription = newPlugin.clientStateSubscription();
        assertTrue(oldSubscription.isCancelled(), "Reload must invalidate old state subscriptions");
        assertTrue(newSubscription != null && !newSubscription.isCancelled());
        assertThrows(IllegalStateException.class,
                () -> oldPlugin.host().clientState().subscribe(batch -> fail("stale owner must be rejected")));
        assertFalse(oldSubscription.cancel(), "Cancelling an already invalidated handle is idempotent");
        assertFalse(newSubscription.isCancelled(), "An old handle cannot cancel the replacement owner's subscription");

        driver.observeClientStateSafePoint(false, 1L, PluginLifecycleCleanupTest::stateSample);
        assertEquals(1, oldPlugin.clientStateBatches().size(), "The replaced owner must receive no later state");
        assertEquals(1, newPlugin.clientStateBatches().size(), "The new owner receives its own initial boundary");
        assertTrue(newPlugin.clientStateBatches().getFirst().initial());

        driver.close();
        assertTrue(newSubscription.isCancelled(), "Close must release the live owner's subscription");
        driver = null;
    }

    @Test
    @DisplayName("Reload Cancels Only The Replaced Client Event Owner")
    void reloadCancelsOnlyTheReplacedClientEventOwner() {
        RecordingLifecyclePlugin.subscribeToClientEventsOnLoad = true;
        initializeDriver();

        RecordingLifecyclePlugin oldPlugin = instance(recordingPluginIds().getFirst());
        var oldSubscription = oldPlugin.clientEventSubscription();
        assertTrue(oldSubscription != null && !oldSubscription.isCancelled());
        driver.observeClientStateSafePoint(false, 1L, PluginLifecycleCleanupTest::stateSample);
        assertEquals(1, oldPlugin.clientEventBatches().size());
        assertTrue(oldPlugin.clientEventBatches().getFirst().initial());

        driver.reload("client event owner reload");

        RecordingLifecyclePlugin newPlugin = instance(recordingPluginIds().getFirst());
        var newSubscription = newPlugin.clientEventSubscription();
        assertTrue(oldSubscription.isCancelled(), "Reload must invalidate old event subscriptions");
        assertTrue(newSubscription != null && !newSubscription.isCancelled());
        assertThrows(IllegalStateException.class,
                () -> oldPlugin.host().clientEvents().subscribe(batch -> fail("stale owner must be rejected")));
        assertFalse(oldSubscription.cancel(), "Cancelling an invalidated event handle is idempotent");
        assertFalse(newSubscription.isCancelled(), "An old handle cannot cancel the replacement owner's subscription");

        driver.observeClientStateSafePoint(false, 1L, PluginLifecycleCleanupTest::stateSample);
        assertEquals(1, oldPlugin.clientEventBatches().size(), "The replaced owner must receive no later event batch");
        assertEquals(1, newPlugin.clientEventBatches().size(), "The new owner receives its own initial boundary");
        assertTrue(newPlugin.clientEventBatches().getFirst().initial());

        driver.close();
        assertTrue(newSubscription.isCancelled(), "Close must release the live owner's event subscription");
        driver = null;
    }

    @Test
    @DisplayName("Unload Failure Does Not Block Other Plugins")
    void unloadFailureDoesNotBlockOtherPlugins() {
        initializeDriver();
        String previousId = recordingPluginIds().get(0);
        RecordingLifecyclePlugin previous = instance(previousId);
        assertTrue(driver.loadedPlugins().contains(FakeTestPlugin.ID.toString()),
                "The fake plugin must load too: " + driver.loadedPlugins());

        RecordingLifecyclePlugin.armUnloadFailure(previousId);
        driver.reload("卸载失败隔离测试");

        assertTrue(previous.unloadFailureCount() >= 1,
                "The failing instance must have attempted onUnload and thrown");
        assertFalse(previous.unloaded(),
                "A throwing onUnload cannot record a completed unload");
        String nextId = recordingPluginIds().get(0);
        assertNotSame(previousId, nextId,
                "The loader must continue and load a fresh instance: " + nextId);
        assertTrue(driver.isInitialized(), "The loader must stay usable after an unload failure");
        assertTrue(driver.loadedPlugins().contains(FakeTestPlugin.ID.toString()),
                "The other plugin must remain loaded: " + driver.loadedPlugins());

        // The throwing instance is still released from the active set.
        assertFalse(driver.loadedPlugins().contains(previousId),
                "A failed unload must not keep the instance loaded: " + driver.loadedPlugins());
        assertTrue(((HostServicesImpl) instance(nextId).host()).isActive(),
                "The replacement instance must keep working after another instance's unload failure");
    }

    @Test
    @DisplayName("Pipeline Switch Does Not Unload Plugins")
    void pipelineSwitchDoesNotUnloadPlugins() {
        initializeDriver();
        String id = recordingPluginIds().get(0);
        RecordingLifecyclePlugin plugin = instance(id);
        assertTrue(driver.registeredPipelineCount() >= 3, "Pipelines must be registered");

        driver.deactivatePipeline("管线切换测试");
        driver.activatePipeline(FakeTestPlugin.ALWAYS_OK_PIPELINE, "管线切换测试");

        assertFalse(plugin.unloaded(),
                "Pipeline deactivation/activation belongs to the pipeline, not to plugin unload");
        assertEquals(0, plugin.unloadCount(), "A pipeline switch must not unload plugins");
        assertTrue(driver.loadedPlugins().contains(id), "The plugin must remain loaded");
    }

    @Test
    @DisplayName("Failed onLoad Is Rolled Back Immediately")
    void failedOnLoadIsRolledBackImmediately() {
        RecordingLifecyclePlugin.subscribeToClientStateOnLoad = true;
        RecordingLifecyclePlugin.armNextLoadFailure();

        initializeDriver();

        // The driver records the failed instance for cleanup and the reservation proves which object
        // this test armed, so the assertions below cannot pass because of an unrelated driver.
        assertTrue(RecordingLifecyclePlugin.instances().size() >= 1,
                "The fixture must construct an instance for this driver");
        RecordingLifecyclePlugin plugin = RecordingLifecyclePlugin.instances().values().stream()
                .reduce((first, second) -> second).orElseThrow();
        String failedId = plugin.instanceId();
        assertTrue(plugin.armedToFailLoad(),
                "The reserved instance must be the one this test armed, not an unrelated driver's");
        assertEquals(1, plugin.loadCount(), "onLoad must have been attempted once");
        assertFalse(recordingPluginIds().contains(failedId),
                "A failed instance must leave the active set instead of staying loaded: "
                        + recordingPluginIds());
        assertTrue(plugin.unloaded(),
                "A failed load must release its resources immediately, not when the loader closes");
        assertTrue(plugin.clientStateSubscription().isCancelled(),
                "A failed onLoad must cancel its state subscription during rollback");
        assertEquals(1, plugin.unloadCount(), "A failed load must unload exactly once");
        assertTrue(driver.pipelines().stream().map(info -> info.id()).noneMatch(plugin.pipelineId()::equals),
                "A pipeline from a failed instance must not stay registered: " + driver.pipelines());
        assertFalse(driver.sceneContributorIds().contains(plugin.contributorId()),
                "A contribution from a failed instance must be released: " + driver.sceneContributorIds());
        assertFalse(driver.capabilities().atLeast("dev.luxloader.test.recording_lifecycle",
                        dev.luxloader.api.capability.CapabilityLevel.NATIVE),
                "A capability claimed by a failed instance must be revoked");
        assertTrue(driver.loadedPlugins().contains(FakeTestPlugin.ID.toString()),
                "Other plugins must keep loading after the failure: " + driver.loadedPlugins());
        assertEquals(3, driver.registeredPipelineCount(),
                "Only pipelines from plugins that loaded successfully remain: " + driver.pipelines());

        driver.close();
        assertEquals(1, plugin.unloadCount(),
                "Close must not clean up an already rolled-back instance again");
        driver = null;
    }

    @Test
    @DisplayName("Failed Reload Is Rolled Back And Can Retry")
    void failedReloadIsRolledBackAndCanRetry() {
        initializeDriver();
        assertEquals(1, recordingPluginIds().size(),
                "One recording instance must be loaded before the failed reload");
        String healthyBefore = recordingPluginIds().get(0);

        RecordingLifecyclePlugin.armNextLoadFailure();
        driver.reload("加载失败回滚测试");

        assertTrue(recordingPluginIds().isEmpty(),
                "A failed reload must not leave the instance in the active set: " + recordingPluginIds());
        List<RecordingLifecyclePlugin> armed = RecordingLifecyclePlugin.instances().values().stream()
                .filter(RecordingLifecyclePlugin::armedToFailLoad).toList();
        assertEquals(1, armed.size(),
                "Exactly the reserved instance must carry the armed failure: " + armed.size());
        RecordingLifecyclePlugin failed = armed.get(0);
        assertNotSame(healthyBefore, failed.instanceId(),
                "The failed reload must be a new instance, not the instance loaded before it");
        assertEquals(1, failed.loadCount(), "The failed reload must have attempted onLoad once");
        assertTrue(failed.unloaded(), "The failed reload must release the instance immediately");
        assertEquals(1, failed.unloadCount(), "The failed reload must release exactly once");
        assertFalse(driver.sceneContributorIds().contains(failed.contributorId()),
                "The failed reload's contribution must be released: " + driver.sceneContributorIds());
        assertTrue(driver.isInitialized(), "The loader must remain usable after a failed reload");
        assertTrue(instance(healthyBefore).unloaded(),
                "The reload must still unload the instance it replaced");

        driver.reload("加载失败后重试");

        List<String> retried = recordingPluginIds();
        assertEquals(1, retried.size(), "A later reload must load a healthy instance: " + retried);
        RecordingLifecyclePlugin healthy = instance(retried.get(0));
        assertNotSame(failed.instanceId(), healthy.instanceId(),
                "The retry must construct a new instance rather than reusing the failed one");
        assertFalse(healthy.unloaded(), "The retried instance must stay loaded");
        assertFalse(driver.sceneContributorIds().contains(failed.contributorId()),
                "The failed instance's contribution must not come back through the retry");
        assertEquals(List.of(healthy.contributorId()), driver.sceneContributorIds(),
                "Only the retried instance's contribution may be registered: " + driver.sceneContributorIds());
    }

    @Test
    @DisplayName("Scene Contributions Follow Plugin Ownership")
    void sceneContributionsFollowPluginOwnership() {
        initializeDriver();
        String firstId = recordingPluginIds().get(0);
        GpuId firstContribution = instance(firstId).contributorId();
        assertEquals(List.of(firstContribution), driver.sceneContributorIds(),
                "The plugin's contribution must be registered while it is loaded");

        driver.reload("场景贡献归属测试");

        String secondId = recordingPluginIds().get(0);
        GpuId secondContribution = instance(secondId).contributorId();
        assertEquals(List.of(secondContribution), driver.sceneContributorIds(),
                "Reload must re-register the contribution from the new instance only: "
                        + driver.sceneContributorIds());
        assertFalse(driver.sceneContributorIds().contains(firstContribution),
                "The previous owner's registration must not survive its unload");
        assertTrue(instance(firstId).unloaded(),
                "The previous owner must be unloaded so its registration cannot survive");

        // The replaced instance must not be able to release anything: its services object is stale.
        try {
            instance(firstId).host().deregisterSceneContributor(secondContribution);
            fail("A replaced plugin instance must not release the current owner's contribution");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(firstId),
                    "Report the stale instance: " + expected.getMessage());
        }
        assertEquals(List.of(secondContribution), driver.sceneContributorIds(),
                "A rejected release must not change the registry");

        driver.deactivatePipeline("场景贡献归属测试");
        driver.close();
        driver = null;
    }

    @Test
    @DisplayName("Scene Contributor Outside The Plugin Namespace Is Rejected")
    void sceneContributorOutsidePluginNamespaceIsRejected() {
        initializeDriver();
        String id = recordingPluginIds().get(0);
        HostServicesImpl host = driver.hostServicesFor(id).orElseThrow();

        // The failure is isolated to the offending plugin: the loader stays usable.
        try {
            host.registerSceneContributor(new GpuId("dev.luxloader.test", "other-plugin/terrain"),
                    scene -> List.of());
            fail("A contribution ID outside the plugin's own namespace must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(id), expected.getMessage());
        }
        assertEquals(List.of(instance(id).contributorId()), driver.sceneContributorIds(),
                "A rejected registration must not enter the registry");
        assertTrue(driver.isInitialized(), "The rejection must not break the loader");
    }

    @Test
    @DisplayName("A Plugin Can Release Its Own Contribution")
    void pluginCanReleaseItsOwnContribution() {
        RecordingLifecyclePlugin.deregisterContributorOnLoad = true;

        initializeDriver();

        String id = recordingPluginIds().get(0);
        assertTrue(driver.sceneContributorIds().isEmpty(),
                "A contribution released during onLoad must be gone: " + driver.sceneContributorIds());

        // Releasing again is a no-op, and the loader keeps working.
        instance(id).host().deregisterSceneContributor(instance(id).contributorId());
        assertTrue(driver.sceneContributorIds().isEmpty(), "Releasing an unknown contribution is ignored");
    }

    @Test
    @DisplayName("Scene Contribution Merge Is Isolated Per Contributor")
    void sceneContributionMergeIsIsolatedPerContributor() {
        initializeDriver();

        // A plugin that throws from contribute must not stop the frame or the other contributors.
        String ownerId = recordingPluginIds().get(0);
        HostServicesImpl host = driver.hostServicesFor(ownerId).orElseThrow();
        GpuId survivingId = GpuId.parse(ownerId).child("surviving");
        GpuId throwingId = GpuId.parse(ownerId).child("throwing");
        AtomicInteger survivingCalls = new AtomicInteger();
        host.registerSceneContributor(survivingId, scene -> {
            survivingCalls.incrementAndGet();
            return List.of(sampleMesh("merged"));
        });
        AtomicInteger throwingCalls = new AtomicInteger();
        host.registerSceneContributor(throwingId, scene -> {
            throwingCalls.incrementAndGet();
            throw new IllegalStateException("测试用：贡献故意失败");
        });

        SceneSnapshot scene = SceneSnapshot.empty(1L);
        List<DynamicSceneMesh> merged = driver.mergeSceneContributions(scene, List.of());

        assertEquals(1, merged.size(),
                "Meshes from healthy contributors must reach the frame: " + merged.size());
        assertEquals(1, survivingCalls.get(), "A healthy contributor must run once per frame");
        assertEquals(1, throwingCalls.get(), "A failing contributor must be attempted");
        assertTrue(merged.get(0).albedo().name().contains("merged"),
                "Keep the healthy contributor's mesh binding: " + merged.get(0).albedo().describe());

        // Contribution is per frame; the loader must not cache one frame's mesh list.
        List<DynamicSceneMesh> second = driver.mergeSceneContributions(scene, List.of());
        assertEquals(1, second.size(), "Rebuild contributions for every frame");
        assertEquals(2, survivingCalls.get(),
                "A second merge call must invoke each healthy contributor exactly once more");
        assertEquals(2, throwingCalls.get(), "A failing contributor is retried, not silently dropped");
    }

    @Test
    @DisplayName("Replaced Instance Services Cannot Touch The New Session")
    void replacedInstanceServicesCannotTouchTheNewSession() {
        initializeDriver();
        String pluginId = FakeTestPlugin.ID.toString();
        HostServicesImpl staleHost = driver.hostServicesFor(pluginId).orElseThrow();
        GpuId shared = FakeTestPlugin.ID.child("review");
        staleHost.registerSceneContributor(shared, scene -> List.of());

        driver.reload("固定插件 ID 失效测试");

        HostServicesImpl newHost = driver.hostServicesFor(pluginId).orElseThrow();
        assertNotSame(staleHost, newHost,
                "Reload must replace the services object even when the plugin ID is unchanged");
        assertFalse(staleHost.isActive(), "The replaced instance's services object must be inactive");
        assertTrue(newHost.isActive(), "The current instance's services object must stay active");

        newHost.registerSceneContributor(shared, scene -> List.of());
        assertTrue(driver.sceneContributorIds().contains(shared),
                "The new instance owns the stable contribution ID after a reload: "
                        + driver.sceneContributorIds());

        assertThrows(IllegalStateException.class, () -> staleHost.deregisterSceneContributor(shared),
                "A replaced instance must not release the new instance's contribution");
        assertTrue(driver.sceneContributorIds().contains(shared),
                "A rejected stale release must not change the registry");

        AtomicInteger staleCalls = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> staleHost.registerSceneContributor(shared, scene -> {
                    staleCalls.incrementAndGet();
                    return List.of();
                }), "A replaced instance must not register a live contribution again");
        driver.mergeSceneContributions(SceneSnapshot.empty(1L), List.of());
        assertEquals(0, staleCalls.get(),
                "A callback registered by a replaced instance must never run");
        assertTrue(driver.sceneContributorIds().contains(shared),
                "The stale registration attempt must not replace the current owner");

        // Every other state-changing entry point of the replaced instance is closed as well.
        assertThrows(IllegalStateException.class, () -> staleHost.registerPipeline(
                        FakeTestPlugin.ID.child("stale"),
                        dev.luxloader.api.pipeline.PipelineDescriptor
                                .builder(FakeTestPlugin.ID.child("stale"), "Stale", "1.0.0").build(),
                        FakeTestPlugin.AlwaysOkPipeline::new),
                "A replaced instance must not register pipelines");
        assertThrows(IllegalStateException.class,
                () -> staleHost.registerCapability("dev.luxloader.test.stale", true, "stale"),
                "A replaced instance must not register capabilities");
        assertThrows(IllegalStateException.class,
                () -> staleHost.registerNativeLibraryPath("natives/windows-x64"),
                "A replaced instance must not claim native library paths");
        assertThrows(IllegalStateException.class, () -> staleHost.requestReload("stale request"),
                "A replaced instance must not request a reload");
        assertThrows(IllegalStateException.class,
                () -> staleHost.requestPipelineSwitch(FakeTestPlugin.ALWAYS_OK_PIPELINE, "stale request"),
                "A replaced instance must not switch the current pipeline");
        assertTrue(driver.isInitialized(), "Rejected stale calls must not break the loader");
        assertFalse(driver.processPendingActions(),
                "A rejected stale request must not leave a pending reload or switch behind");
        assertTrue(driver.pipelines().stream().map(info -> info.id())
                        .noneMatch(FakeTestPlugin.ID.child("stale")::equals),
                "The stale pipeline must not appear in the registry");
    }

    @Test
    @DisplayName("Close Rejects Later Calls From Released Services")
    void closeRejectsLaterCallsFromReleasedServices() {
        initializeDriver();
        HostServicesImpl host = driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow();
        GpuId id = FakeTestPlugin.ID.child("after-close");
        host.registerSceneContributor(id, scene -> List.of());

        driver.close();
        driver = null;

        assertFalse(host.isActive(), "A closed loader must leave no active services object");
        assertThrows(IllegalStateException.class, () -> host.registerSceneContributor(id, scene -> List.of()),
                "A released services object must not register after close");
        assertThrows(IllegalStateException.class, () -> host.deregisterSceneContributor(id),
                "A released services object must not release after close");
        assertThrows(IllegalStateException.class, () -> host.requestReload("after close"),
                "A released services object must not request work after close");
    }

    @Test
    @DisplayName("Contribution Table Changes Take Effect At The Merge Boundary")
    void contributionTableChangesTakeEffectAtTheMergeBoundary() {
        initializeDriver();
        HostServicesImpl host = driver.hostServicesFor(recordingPluginIds().get(0)).orElseThrow();
        GpuId owner = GpuId.parse(host.ownerPluginId());
        AtomicInteger selfCalls = new AtomicInteger();
        AtomicInteger survivorCalls = new AtomicInteger();
        GpuId selfId = owner.child("boundary-self");
        GpuId survivorId = owner.child("boundary-survivor");
        host.registerSceneContributor(selfId, scene -> {
            selfCalls.incrementAndGet();
            host.deregisterSceneContributor(selfId);
            return List.of();
        });
        host.registerSceneContributor(survivorId, scene -> {
            survivorCalls.incrementAndGet();
            return List.of();
        });

        // A contributor releasing itself must not break the pass; the remaining contributors still run.
        driver.mergeSceneContributions(SceneSnapshot.empty(1L), List.of());
        assertEquals(1, selfCalls.get(), "The self-releasing contributor runs in the pass that started first");
        assertEquals(1, survivorCalls.get(), "A later contributor must still run in the same pass");
        assertFalse(driver.sceneContributorIds().contains(selfId),
                "The release must be visible immediately after the pass: " + driver.sceneContributorIds());

        driver.mergeSceneContributions(SceneSnapshot.empty(2L), List.of());
        assertEquals(1, selfCalls.get(), "A released contributor must not run in the next pass");
        assertEquals(2, survivorCalls.get(), "The remaining contributor must keep running every pass");
    }

    @Test
    @DisplayName("A Contributor Can Release A Later Contributor")
    void contributorCanReleaseALaterContributor() {
        initializeDriver();
        HostServicesImpl host = driver.hostServicesFor(recordingPluginIds().get(0)).orElseThrow();
        GpuId owner = GpuId.parse(host.ownerPluginId());
        AtomicInteger removerCalls = new AtomicInteger();
        AtomicInteger victimCalls = new AtomicInteger();
        AtomicInteger survivorCalls = new AtomicInteger();
        GpuId removerId = owner.child("boundary-remover");
        GpuId victimId = owner.child("boundary-victim");
        GpuId survivorId = owner.child("boundary-late-survivor");
        // The victim is registered first so that the remover, which runs in the same pass, removes an
        // entry that the pass has not reached yet.
        host.registerSceneContributor(victimId, scene -> {
            victimCalls.incrementAndGet();
            return List.of();
        });
        host.registerSceneContributor(removerId, scene -> {
            removerCalls.incrementAndGet();
            host.deregisterSceneContributor(victimId);
            return List.of();
        });
        host.registerSceneContributor(survivorId, scene -> {
            survivorCalls.incrementAndGet();
            return List.of();
        });

        driver.mergeSceneContributions(SceneSnapshot.empty(1L), List.of());
        assertEquals(1, removerCalls.get(), "The removing contributor must run");
        assertEquals(1, victimCalls.get(),
                "A contributor captured by the running pass still runs once before its release takes effect");
        assertEquals(1, survivorCalls.get(), "A contributor after the removed one must still run");
        assertFalse(driver.sceneContributorIds().contains(victimId),
                "The removed contributor must be gone from the table");

        driver.mergeSceneContributions(SceneSnapshot.empty(2L), List.of());
        assertEquals(1, victimCalls.get(), "A released contributor is not invoked again");
        assertEquals(2, removerCalls.get(), "The removing contributor keeps running");
        assertEquals(2, survivorCalls.get(), "Removing one contributor must not affect the others");
    }

    @Test
    @DisplayName("A Contributor Registered During A Merge Runs In The Same Merge")
    void contributorRegisteredDuringMergeRunsInTheSameMerge() {
        initializeDriver();
        HostServicesImpl host = driver.hostServicesFor(recordingPluginIds().get(0)).orElseThrow();
        GpuId owner = GpuId.parse(host.ownerPluginId());
        AtomicInteger adderCalls = new AtomicInteger();
        AtomicInteger addedCalls = new AtomicInteger();
        GpuId adderId = owner.child("boundary-adder");
        GpuId addedId = owner.child("boundary-added");
        host.registerSceneContributor(adderId, scene -> {
            if (adderCalls.incrementAndGet() == 1) {
                host.registerSceneContributor(addedId, inner -> {
                    addedCalls.incrementAndGet();
                    return List.of();
                });
            }
            return List.of();
        });

        driver.mergeSceneContributions(SceneSnapshot.empty(1L), List.of());
        assertEquals(1, adderCalls.get(), "The adding contributor must run");
        assertEquals(1, addedCalls.get(),
                "A contributor registered during the merge must run in the same merge");

        driver.mergeSceneContributions(SceneSnapshot.empty(2L), List.of());
        assertEquals(2, adderCalls.get(), "The adding contributor keeps running");
        assertEquals(2, addedCalls.get(), "The added contributor keeps running on later merges");
    }

    /** Small valid mesh used to observe merged contributions. */
    private static DynamicSceneMesh sampleMesh(String name) {
        CompiledSceneMesh mesh = new CompiledSceneMesh(name, dev.luxloader.api.scene.MeshChunk.Kind.OTHER,
                0, 0, 0, 4, List.of(), new byte[] {0, 0, 0, 0}, new byte[0],
                dev.luxloader.api.scene.MeshChunk.IndexType.UNSIGNED_SHORT, 0, false);
        SceneImage image = new SceneImage(name, dev.luxloader.api.gpu.ImageHandle.vkImage(0x1234L, name),
                dev.luxloader.api.gpu.GpuFormat.R8G8B8A8_UNORM, 16, 16);
        return new DynamicSceneMesh(mesh, image, 0.5f);
    }

    private static ClientStateSnapshot stateSample(long sequence, ClientStateSnapshot.LogicalTime time) {
        var world = new ClientStateSnapshot.WorldSession(1L, true, "minecraft:overworld");
        var environment = new ClientStateSnapshot.Environment(true, "minecraft:overworld", 6000L,
                0f, 0f, ClientStateSnapshot.BiomeSample.UNAVAILABLE);
        var identity = new ClientStateSnapshot.EntityIdentity(1L, 4,
                "a32e1a6a-1f85-4ab4-96f0-64d2162611fa", 1L);
        var player = new ClientStateSnapshot.Player(true, identity, 0d, 64d, 0d, 0f, 0f,
                0d, 0d, 0d, true, false, false, false, false,
                ClientStateSnapshot.Pose.STANDING, false, 0f, 0f, false, -1,
                ClientStateSnapshot.ItemDescription.EMPTY, ClientStateSnapshot.ItemDescription.EMPTY);
        return new ClientStateSnapshot(sequence, time, world, environment, player);
    }
}
