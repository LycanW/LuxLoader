package dev.luxloader.core.capability;

import dev.luxloader.api.capability.CapabilityDescriptor;
import dev.luxloader.api.capability.CapabilityLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic concurrency regression for the effective-value snapshot.
 *
 * <p>Two rebuilds publish in an order that keeps the older table last, which is exactly what a
 * scheduler may produce when a release overlaps a rebuild. The test forces that order through the
 * documented publication probe instead of relying on sleeps, line numbers or lucky pressure, and
 * then checks that a quiescent query still sees the released provider. It also covers the
 * mutating paths that share the same cache: a rejected claim must not change the table, and
 * {@code clear()} must invalidate it.
 */
class CapabilitySnapshotPublicationTest {

    private static final String CAP = "test.snapshot.capability";

    @AfterEach
    void tearDown() {
        CapabilityRegistryImpl.setPublishProbe(null);
    }

    @Test
    @DisplayName("A Rebuild That Sampled Before A Release Cannot Be Current Afterwards")
    void rebuildThatSampledBeforeAReleaseCannotBeCurrentAfterwards() throws Exception {
        CapabilityRegistryImpl registry = new CapabilityRegistryImpl();
        registry.claim("healthy", CapabilityDescriptor.of(CAP, CapabilityLevel.NATIVE, "healthy"));
        registry.claim("failed", CapabilityDescriptor.of(CAP, CapabilityLevel.FULL, "failed"));

        // A rebuild that sampled sources before the release must not be able to mark its older table as
        // current. That is what the probe forces here: the pre-release rebuild samples both claims and
        // parks, the release lands, a later read publishes the post-release table, and only then is the
        // parked rebuild allowed to finish. Writing data and stamp separately let the stale table win,
        // after which the release stayed invisible until some unrelated change occurred.
        CountDownLatch preReleaseRebuildParked = new CountDownLatch(1);
        CountDownLatch resumePreRelease = new CountDownLatch(1);
        CountDownLatch probePassed = new CountDownLatch(1);
        CapabilityRegistryImpl.setPublishProbe(() -> {
            if (preReleaseRebuildParked.getCount() > 0) {
                preReleaseRebuildParked.countDown();
                await(resumePreRelease);
                return;
            }
            // Every later rebuild (including the one the resume unblocks) proceeds immediately.
            probePassed.countDown();
        });

        Thread preReleaseReader = new Thread(() -> registry.find(CAP), "snapshot-pre-release-reader");
        preReleaseReader.start();
        assertTrue(await(preReleaseRebuildParked), "The pre-release rebuild must reach publication");
        assertEquals("failed", registry.find(CAP).orElseThrow().provider(),
                "The parked rebuild sampled sources that still contain the failed claim");

        registry.releaseClaims("failed");
        assertEquals(List.of("healthy"), registry.claimsByCapability().get(CAP),
                "Only the healthy claim remains after the release");

        // A read now builds the post-release table while the parked rebuild still holds the old one.
        assertEquals("healthy", registry.find(CAP).orElseThrow().provider(),
                "A read after the release must observe the remaining provider");

        // Let the parked rebuild finish last.
        resumePreRelease.countDown();
        assertTrue(await(probePassed), "The parked rebuild must be able to finish");
        preReleaseReader.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(preReleaseReader.isAlive(), "The pre-release rebuild must finish");

        // Quiescent state: every thread has stopped and no source changed since the release, so a query
        // must observe the remaining provider and the published table must carry the current version.
        assertEquals("healthy", registry.find(CAP).orElseThrow().provider(),
                "A completed release must not be undone by an older rebuild");
        assertEquals(registry.sourceVersion(), registry.publishedVersion(),
                "A stale rebuild must not mark its table as current");
        for (int i = 0; i < 100; i++) {
            assertEquals("healthy", registry.find(CAP).orElseThrow().provider(),
                    "Stale provider survived quiescent read " + i);
        }
        assertEquals(registry.sourceVersion(), registry.publishedVersion(),
                "Quiescent reads must not leave a stale table marked as current");
        assertEquals(CapabilityLevel.NATIVE, registry.level(CAP),
                "The effective level must come from the remaining provider");
    }

    @Test
    @DisplayName("Same Owner Downgrade Is Rejected And Keeps Its Claim")
    void sameOwnerDowngradeIsRejectedAndKeepsItsClaim() {
        CapabilityRegistryImpl registry = new CapabilityRegistryImpl();
        registry.claim("owner", CapabilityDescriptor.of(CAP, CapabilityLevel.NATIVE, "owner"));
        assertEquals(CapabilityLevel.NATIVE, registry.level(CAP));

        // The rejection rule is per owner: a lower level from the same owner is refused, so neither the
        // effective value nor the recorded claim changes. A lower level from a *different* owner is not
        // a rejection at all — it is kept as a fallback source, which is what lets the effective value
        // fall back to another provider when the high-level one is released.
        assertFalse(registry.claim("owner", CapabilityDescriptor.of(CAP, CapabilityLevel.FALLBACK, "owner")),
                "A downgrade from the same owner must be rejected");
        assertEquals(CapabilityLevel.NATIVE, registry.level(CAP),
                "A rejected downgrade must not replace the effective value");
        assertEquals(java.util.List.of("owner"), registry.claimsByCapability().get(CAP),
                "A rejected downgrade must not add a second claim");
    }

    @Test
    @DisplayName("Lower Claim From Another Owner Is Kept As A Fallback Source")
    void lowerClaimFromAnotherOwnerIsKeptAsAFallbackSource() {
        CapabilityRegistryImpl registry = new CapabilityRegistryImpl();
        registry.claim("high", CapabilityDescriptor.of(CAP, CapabilityLevel.NATIVE, "high"));
        assertTrue(registry.claim("low", CapabilityDescriptor.of(CAP, CapabilityLevel.FALLBACK, "low")),
                "A lower claim from another owner must be accepted as a spare source");
        assertEquals(CapabilityLevel.NATIVE, registry.level(CAP),
                "The higher level stays effective while both claims exist");
        assertTrue(registry.claimsByCapability().get(CAP).contains("low"),
                "The spare source must be recorded for fallback");

        registry.releaseClaims("high");
        assertEquals(CapabilityLevel.FALLBACK, registry.level(CAP),
                "Releasing the high-level owner must fall back to the spare source instead of UNSUPPORTED");
        assertEquals("low", registry.find(CAP).orElseThrow().provider(),
                "The spare source becomes the effective provider");
        assertEquals(java.util.List.of("low"), registry.claimsByCapability().get(CAP),
                "Only the released owner's claim is removed");
    }

    @Test
    @DisplayName("Clear Publishes An Empty Table With The Current Version")
    void clearPublishesAnEmptyTableWithTheCurrentVersion() {
        CapabilityRegistryImpl registry = new CapabilityRegistryImpl();
        registry.claim("owner", CapabilityDescriptor.of(CAP, CapabilityLevel.NATIVE, "owner"));
        registry.register(CapabilityDescriptor.of(CAP + ".independent", CapabilityLevel.PARTIAL, "device-probe"));
        assertEquals(CapabilityLevel.NATIVE, registry.level(CAP));

        registry.clear();

        assertEquals(CapabilityLevel.UNSUPPORTED, registry.level(CAP),
                "Clear must invalidate claimed values");
        assertEquals(CapabilityLevel.UNSUPPORTED, registry.level(CAP + ".independent"),
                "Clear must invalidate independent registrations");
        assertFalse(registry.isRegistered(CAP), "Clear must publish an empty table");
        assertTrue(registry.all().isEmpty(), "Clear must leave no effective entries: " + registry.all());

        // Rebuilding after clear must not resurrect anything from a snapshot built before it.
        registry.claim("second", CapabilityDescriptor.of(CAP, CapabilityLevel.FALLBACK, "second"));
        assertEquals("second", registry.find(CAP).orElseThrow().provider(),
                "A post-clear claim must be the effective provider");
    }

    @Test
    @DisplayName("Concurrent Readers And Releases Settle On The Remaining Providers")
    void concurrentReadersAndReleasesSettleOnTheRemainingProviders() throws Exception {
        CapabilityRegistryImpl registry = new CapabilityRegistryImpl();
        String[] ids = {"concurrent.a", "concurrent.b", "concurrent.c", "concurrent.d"};
        for (String id : ids) {
            registry.claim("healthy", CapabilityDescriptor.of(id, CapabilityLevel.NATIVE, "healthy"));
            registry.claim("failed", CapabilityDescriptor.of(id, CapabilityLevel.FULL, "failed"));
        }

        int readers = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        for (int i = 0; i < readers; i++) {
            Thread reader = new Thread(() -> {
                await(start);
                for (int round = 0; round < 500; round++) {
                    for (String id : ids) {
                        registry.find(id);
                    }
                }
                done.countDown();
            }, "snapshot-reader-" + i);
            reader.setDaemon(true);
            reader.start();
        }
        start.countDown();
        registry.releaseClaims("failed");
        assertTrue(await(done), "All readers must finish");

        for (String id : ids) {
            assertEquals("healthy", registry.find(id).orElseThrow().provider(),
                    "After every operation completes the remaining provider must be effective: " + id);
        }
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
