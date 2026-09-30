package dev.luxloader.mc;

import dev.luxloader.api.presentation.PresentationService;
import dev.luxloader.api.resource.ResourceKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedSoundObservationTest {
    @Test void staleEnableCannotReviveTheListenerAfterLastSubscriptionClosed() {
        try (var observation = new ManagedSoundObservation()) {
            var delivered = new AtomicInteger();
            assertFalse(observation.configure(2, false, sound -> delivered.incrementAndGet()));
            assertFalse(observation.configure(1, true, sound -> fail("Stale sink")));
            assertFalse(observation.enabled());
            observation.capture(false, true, () -> { fail("Disabled observation must not copy sound fields"); return null; });
            assertEquals(0, delivered.get());
        }
    }

    @Test void allManagedSourcesAndUnsupportedThreadsAreFilteredBeforePayloadCopy() {
        try (var observation = new ManagedSoundObservation()) {
            var delivered = new ArrayList<PresentationService.HostSound>(); var copies = new AtomicInteger();
            var host = new PresentationService.HostSound(new ResourceKey("minecraft", "block.stone.step"),
                    new ResourceKey("minecraft", "sounds/step/stone1.ogg"), PresentationService.Parameters.at(0, 64, 0));
            assertTrue(observation.configure(1, true, delivered::add));
            // The host passes true for every ManagedSoundInstance, regardless of its plugin/owner.
            for (int owner = 0; owner < 100; owner++) observation.capture(true, true, () -> { copies.incrementAndGet(); return host; });
            observation.capture(false, false, () -> { copies.incrementAndGet(); return host; });
            assertEquals(0, copies.get()); assertTrue(delivered.isEmpty());
            observation.capture(false, true, () -> { copies.incrementAndGet(); return host; });
            assertEquals(1, copies.get()); assertEquals(java.util.List.of(host), delivered);
            observation.close(); observation.configure(2, true, delivered::add);
            observation.capture(false, true, () -> { copies.incrementAndGet(); return host; });
            assertEquals(1, copies.get()); assertFalse(observation.enabled());
        }
    }
}
