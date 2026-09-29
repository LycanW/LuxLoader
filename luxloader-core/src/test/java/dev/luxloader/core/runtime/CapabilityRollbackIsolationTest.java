package dev.luxloader.core.runtime;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import dev.luxloader.api.plugin.RenderDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capability-claim rollback isolation without a GPU. A failed plugin instance may only lose the
 * claims it actually made: a rejected claim must not become a licence to revoke another provider, an
 * accepted override must restore the provider it replaced, and a later healthy claim must survive.
 *
 * <p>Competing declarations are staged while the failing instance's {@code onLoad} is still running,
 * so every case goes through the real {@code initialize} failure path rather than a reflective call
 * into the rollback method.
 */
class CapabilityRollbackIsolationTest {

    private static final String SHARED = "dev.luxloader.test.shared_capability";

    @TempDir
    Path configDir;

    /** Written by the loader thread and published to this thread by the thread join. */
    private RenderDriverImpl driver;

    private RecordingLifecyclePlugin failed;

    @BeforeEach
    void setUp() {
        RecordingLifecyclePlugin.reset();
        FakeTestPlugin.reset();
    }

    @AfterEach
    void tearDown() {
        RecordingLifecyclePlugin.releaseLoadRendezvous();
        if (driver != null) {
            driver.close();
            driver = null;
        }
        RecordingLifecyclePlugin.reset();
        FakeTestPlugin.reset();
    }

    /**
     * Initializes a driver whose recording plugin fails during onLoad, while {@code stage} runs on this
     * thread with that instance still loaded. This is the production failure path: the claims staged by
     * {@code stage} exist before the rollback, and the rollback is reached because onLoad throws.
     *
     * <p>The driver object is created here and initialized on a worker thread, so this thread can read
     * the live registry during the rendezvous without waiting for initialization to return.
     */
    private void initializeWithStagedClaims(Consumer<HostServicesImpl> stage) {
        driver = new RenderDriverImpl(configDir);
        CountDownLatch entered = RecordingLifecyclePlugin.stageLoadRendezvous();
        RecordingLifecyclePlugin.armNextLoadFailure();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread loader = new Thread(() -> {
            try {
                driver.initialize(RenderDriver.DeviceRequest.attachedToGame());
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "capability-rollback-loader");
        loader.start();
        boolean reached = awaitLatch(entered);
        Throwable stagingFailure = null;
        try {
            if (!reached) {
                stagingFailure = new AssertionError(
                        "The plugin did not reach its onLoad rendezvous: " + failure.get());
            } else {
                RecordingLifecyclePlugin failedInstance = RecordingLifecyclePlugin.instances().values().stream()
                        .filter(RecordingLifecyclePlugin::armedToFailLoad).findFirst().orElseThrow();
                failed = failedInstance;
                HostServicesImpl failedHost = driver.hostServicesFor(failedInstance.instanceId()).orElseThrow();
                if (!failedHost.isActive()) {
                    stagingFailure = new AssertionError(
                            "Staging must happen while the failing instance is still loaded");
                } else {
                    stage.accept(failedHost);
                }
            }
        } catch (Throwable t) {
            stagingFailure = t;
        } finally {
            // Always let the loader thread finish, so its rollback is complete before any assertion and
            // no background thread outlives the test.
            RecordingLifecyclePlugin.releaseLoadRendezvous();
        }
        join(loader);
        if (stagingFailure != null) {
            throw new AssertionError("Staging failed: " + stagingFailure, stagingFailure);
        }
        assertNull(failure.get(),
                "Driver initialization must not propagate the plugin failure: " + failure.get());
        assertTrue(failed.unloaded(), "The real onLoad failure path must have rolled the instance back");
        assertFalse(driver.loadedPlugins().contains(failed.instanceId()),
                "The failed instance must have left the active set");
    }

    private HostServicesImpl healthyHost() {
        return driver.hostServicesFor(FakeTestPlugin.ID.toString()).orElseThrow();
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Waits for the loader thread. The bound exceeds the rendezvous wait in the fixture, so a plugin
     * that is still blocked in onLoad is always resolved by releasing the rendezvous first.
     */
    private static void join(Thread thread) {
        try {
            thread.join(60_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(thread.isAlive(), "The loader thread must finish before assertions run");
    }

    @Test
    @DisplayName("Lower Claim From Another Owner Cannot Revoke The Effective Provider")
    void lowerClaimFromAnotherOwnerCannotRevokeTheEffectiveProvider() {
        // The healthy provider owns NATIVE; the failing instance's lower FALLBACK claim is accepted as
        // a spare source (a claim is only rejected when the same owner tries to downgrade), never as the
        // effective provider. The rollback replays the production release path for that owner, because a
        // staged claim would otherwise be released by the rollback that runs inside initialize().
        initializeWithStagedClaims(failedHost -> {
            healthyHost().registerCapability(SHARED, CapabilityLevel.NATIVE, "Healthy provider");
            assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED));
            failedHost.registerCapability(SHARED, CapabilityLevel.FALLBACK, "Provider that fails to load");
            assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED),
                    "A lower claim must not replace the effective provider while it is staged");
        });

        driver.releaseFailedPluginCapabilityClaims(failed.instanceId());

        assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED),
                "Rolling back the failed instance must leave the healthy provider effective");
        assertEquals(FakeTestPlugin.ID.toString(),
                driver.capabilities().find(SHARED).orElseThrow().provider(),
                "The effective provider must still be the healthy plugin");
        assertFalse(driver.capabilityClaims().getOrDefault(SHARED, java.util.List.of())
                        .contains(failed.instanceId()),
                "The failed instance's claim must be gone after the rollback: "
                        + driver.capabilityClaims());
        assertTrue(driver.loadedPlugins().contains(FakeTestPlugin.ID.toString()),
                "The healthy provider must stay loaded");
    }

    @Test
    @DisplayName("Accepted Override Is Restored When Its Owner Fails")
    void acceptedOverrideIsRestoredWhenItsOwnerFails() {
        // The failing instance claims FULL after the healthy provider's NATIVE.
        initializeWithStagedClaims(failedHost -> {
            healthyHost().registerCapability(SHARED, CapabilityLevel.NATIVE, "Healthy provider");
            failedHost.registerCapability(SHARED, CapabilityLevel.FULL, "Provider that fails to load");
            assertEquals(CapabilityLevel.FULL, driver.capabilities().level(SHARED),
                    "An accepted claim is effective while its owner loads");
        });

        assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED),
                "Releasing the failed claim must restore the still-valid provider");
        assertEquals(FakeTestPlugin.ID.toString(),
                driver.capabilities().find(SHARED).orElseThrow().provider(),
                "The restored entry must name the healthy provider");
    }

    @Test
    @DisplayName("Later Healthy Claim Is Not Revoked By An Earlier Failed Claim")
    void laterHealthyClaimIsNotRevokedByAnEarlierFailedClaim() {
        // The failing instance claims first, and a healthy provider claims afterwards.
        initializeWithStagedClaims(failedHost -> {
            failedHost.registerCapability(SHARED, CapabilityLevel.FALLBACK, "Provider that fails to load");
            healthyHost().registerCapability(SHARED, CapabilityLevel.NATIVE, "Healthy provider");
            assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED));
        });

        assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED),
                "A healthy claim submitted after the failed one must survive the rollback");
    }

    @Test
    @DisplayName("Releasing All Claims Reports Unsupported")
    void releasingAllClaimsReportsUnsupported() {
        initializeWithStagedClaims(failedHost ->
                failedHost.registerCapability(SHARED, CapabilityLevel.NATIVE, "Provider that fails to load"));

        assertEquals(CapabilityLevel.UNSUPPORTED, driver.capabilities().level(SHARED),
                "Without any valid provider the capability must report unsupported");
        assertFalse(driver.capabilities().isRegistered(SHARED),
                "No claim and no independent registration means no entry");
    }

    @Test
    @DisplayName("Failed Release Does Not Restore A Stale Claim")
    void failedReleaseDoesNotRestoreAStaleClaim() {
        initializeWithStagedClaims(failedHost ->
                failedHost.registerCapability(SHARED, CapabilityLevel.NATIVE, "Provider that fails to load"));

        // Releasing again must be harmless and must not resurrect the released claim.
        assertTrue(driver.releaseFailedPluginCapabilityClaims(failed.instanceId()).isEmpty(),
                "A repeated release has nothing left to release");
        assertEquals(CapabilityLevel.UNSUPPORTED, driver.capabilities().level(SHARED),
                "A repeated release must not restore an already released claim");
    }

    @Test
    @DisplayName("Stale Instance Cannot Claim After Rollback")
    void staleInstanceCannotClaimAfterRollback() {
        initializeWithStagedClaims(failedHost ->
                failedHost.registerCapability(SHARED, CapabilityLevel.NATIVE, "Provider that fails to load"));
        HostServicesImpl failedHost = driver.hostServicesFor(failed.instanceId()).orElseThrow();

        assertThrows(IllegalStateException.class,
                () -> failedHost.registerCapability(SHARED, CapabilityLevel.NATIVE, "late claim"),
                "A rolled-back instance must not be able to claim capabilities");
        assertEquals(CapabilityLevel.UNSUPPORTED, driver.capabilities().level(SHARED),
                "The rejected late claim must not change the registry");
    }

    @Test
    @DisplayName("Independent Registration Is Not Revoked By A Claim Rollback")
    void independentRegistrationIsNotRevokedByAClaimRollback() {
        initializeWithStagedClaims(failedHost -> {
            // A non-claim registration represents a device or host fact, not a plugin claim.
            driver.registerCapability(CapabilityDescriptor.of(SHARED, CapabilityLevel.NATIVE, "device-probe"));
            failedHost.registerCapability(SHARED, CapabilityLevel.FULL, "Provider that fails to load");
        });

        assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED),
                "Rolling back plugin claims must not remove independent device/host registrations");
    }

    @Test
    @DisplayName("Highest Valid Source Wins Regardless Of Submission Order")
    void highestValidSourceWinsRegardlessOfSubmissionOrder() {
        initializeWithStagedClaims(failedHost -> {
            String reverse = SHARED + "_reverse";
            healthyHost().registerCapability(reverse, CapabilityLevel.NATIVE, "Healthy provider");
            driver.registerCapability(CapabilityDescriptor.of(reverse, CapabilityLevel.PARTIAL, "device-probe"));
            assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(reverse),
                    "A later lower-level registration must not downgrade a valid provider");
        });

        assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED + "_reverse"),
                "Claim rollback must leave independent registrations effective");
    }

    @Test
    @DisplayName("Repeated Load Failure Releases Claims Once")
    void repeatedLoadFailureReleasesClaimsOnce() {
        initializeWithStagedClaims(failedHost -> {
            healthyHost().registerCapability(SHARED, CapabilityLevel.NATIVE, "Healthy provider");
            failedHost.registerCapability(SHARED, CapabilityLevel.FULL, "Provider that fails to load");
        });
        assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED));

        // A second cleanup of the same owner must not release anything else.
        assertTrue(driver.releaseFailedPluginCapabilityClaims(failed.instanceId()).isEmpty());
        assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED),
                "Repeated cleanup must not disturb the healthy provider");

        driver.close();
        driver = null;
    }

    @Test
    @DisplayName("Cleanup Of An Unrelated Owner Is Isolated")
    void cleanupOfAnUnrelatedOwnerIsIsolated() {
        initializeWithStagedClaims(failedHost -> {
            healthyHost().registerCapability(SHARED, CapabilityLevel.NATIVE, "Healthy provider");
            failedHost.registerCapability(SHARED, CapabilityLevel.FULL, "Provider that fails to load");
        });

        assertTrue(driver.releaseFailedPluginCapabilityClaims("dev.luxloader.test:not-installed").isEmpty());
        assertTrue(driver.releaseFailedPluginCapabilityClaims("").isEmpty());
        assertTrue(driver.releaseFailedPluginCapabilityClaims(null).isEmpty());
        assertEquals(CapabilityLevel.NATIVE, driver.capabilities().level(SHARED),
                "Cleanup of an unrelated owner must not affect a valid provider");
    }
}
