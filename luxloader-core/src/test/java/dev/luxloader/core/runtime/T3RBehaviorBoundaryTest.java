package dev.luxloader.core.runtime;

import dev.luxloader.api.event.BehaviorEvent;
import dev.luxloader.api.event.ClientEventBatch;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.plugin.RenderDriver;
import dev.luxloader.api.state.ClientStateBatch;
import dev.luxloader.api.state.ClientStateSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production driver/hub boundary tests for T3-R, without Minecraft or a GPU. */
class T3RBehaviorBoundaryTest {
    private static final ClientStateSnapshot.EntityIdentity OLD_IDENTITY =
            new ClientStateSnapshot.EntityIdentity(1L, 7, "same-player", 1L);
    private static final ClientStateSnapshot.EntityIdentity REPLACEMENT_IDENTITY =
            new ClientStateSnapshot.EntityIdentity(1L, 7, "same-player", 2L);

    @TempDir
    Path configDir;

    private RenderDriverImpl driver;

    @BeforeEach
    void setUp() {
        driver = new RenderDriverImpl(configDir);
        driver.initialize(RenderDriver.DeviceRequest.attachedToGame());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    @Test
    @DisplayName("Player event retains its captured identity across same-ID object replacement")
    void playerEventKeepsOldIdentityWhenDeliverySnapshotHasReplacement() throws Exception {
        List<ClientEventBatch> batches = eventBatches();
        driver.observeClientStateSafePoint(false, 1L, snapshot(OLD_IDENTITY));
        assertTrue(captureWithIdentity(OLD_IDENTITY));

        driver.observeClientStateTick(false, snapshot(REPLACEMENT_IDENTITY));
        driver.observeClientStateSafePoint(false, 1L, snapshot(REPLACEMENT_IDENTITY));

        assertEquals(2, batches.size());
        var delivered = batches.getLast();
        assertEquals(REPLACEMENT_IDENTITY, delivered.snapshot().player().identity());
        BehaviorEvent event = (BehaviorEvent) delivered.events().getFirst();
        Method playerIdentity = Arrays.stream(BehaviorEvent.class.getRecordComponents())
                .filter(component -> component.getName().equals("playerIdentity"))
                .map(component -> component.getAccessor())
                .findFirst().orElse(null);
        assertNotNull(playerIdentity,
                "BehaviorEvent must expose the identity copied when the source captured the event");
        assertEquals(Optional.of(OLD_IDENTITY), playerIdentity.invoke(event));
    }

    @Test
    @DisplayName("Event-only subscriber receives a gap when capture recovers before the next safe point")
    void eventOnlyImmediateRecoveryStillResynchronizes() {
        List<ClientEventBatch> batches = eventBatches();
        driver.observeClientStateSafePoint(false, 1L, snapshot(OLD_IDENTITY));
        assertTrue(captureWithoutIdentity());

        driver.observeClientStateTick(false, T3RBehaviorBoundaryTest::failedSnapshot);
        driver.observeClientStateSafePoint(false, 1L, snapshot(REPLACEMENT_IDENTITY));

        assertEquals(2, batches.size());
        assertEquals(ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED, batches.getLast().resyncReason());
        assertTrue(batches.getLast().events().isEmpty(), "Events captured before the gap must be dropped");
        driver.observeClientStateSafePoint(false, 1L, snapshot(REPLACEMENT_IDENTITY));
        assertEquals(2, batches.size(), "A consumed gap must not be repeated at a later healthy boundary");
    }

    @Test
    @DisplayName("State and event subscribers both receive one recovery boundary")
    void stateAndEventStreamsBothResynchronizeAfterImmediateRecovery() {
        List<ClientEventBatch> eventBatches = eventBatches();
        List<ClientStateBatch> stateBatches = new ArrayList<>();
        driver.clientState(92L).subscribe(stateBatches::add);
        driver.observeClientStateSafePoint(false, 1L, snapshot(OLD_IDENTITY));
        assertTrue(captureWithoutIdentity());

        driver.observeClientStateTick(false, T3RBehaviorBoundaryTest::failedSnapshot);
        driver.observeClientStateSafePoint(false, 1L, snapshot(REPLACEMENT_IDENTITY));

        assertEquals(2, stateBatches.size());
        assertEquals(ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED, stateBatches.getLast().resyncReason());
        assertEquals(2, eventBatches.size());
        assertEquals(ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED, eventBatches.getLast().resyncReason());
        assertTrue(eventBatches.getLast().events().isEmpty());
    }

    @Test
    @DisplayName("Persistent capture failure is delivered once after recovery")
    void persistentFailuresProduceOneEventGap() {
        List<ClientEventBatch> batches = eventBatches();
        driver.observeClientStateSafePoint(false, 1L, snapshot(OLD_IDENTITY));
        assertTrue(captureWithoutIdentity());

        driver.observeClientStateTick(false, T3RBehaviorBoundaryTest::failedSnapshot);
        driver.observeClientStateSafePoint(false, 1L, T3RBehaviorBoundaryTest::failedSnapshot);
        driver.observeClientStateTick(false, T3RBehaviorBoundaryTest::failedSnapshot);
        driver.observeClientStateSafePoint(false, 1L, T3RBehaviorBoundaryTest::failedSnapshot);
        driver.observeClientStateSafePoint(false, 1L, snapshot(REPLACEMENT_IDENTITY));

        assertEquals(2, batches.size());
        assertEquals(ClientStateBatch.ResyncReason.SAMPLE_CAPTURE_FAILED, batches.getLast().resyncReason());
        driver.observeClientStateSafePoint(false, 1L, snapshot(REPLACEMENT_IDENTITY));
        assertEquals(2, batches.size(), "Persistent failures collapse into one delivered gap");
    }

    @Test
    @DisplayName("Failure before an event subscriber baseline is folded into its initial sample")
    void initialSubscriptionGetsBaselineInsteadOfSeparateGap() {
        List<ClientEventBatch> batches = eventBatches();
        driver.observeClientStateTick(false, T3RBehaviorBoundaryTest::failedSnapshot);
        driver.observeClientStateSafePoint(false, 1L, snapshot(OLD_IDENTITY));

        assertEquals(1, batches.size());
        assertTrue(batches.getFirst().initial());
        assertEquals(ClientStateBatch.ResyncReason.NONE, batches.getFirst().resyncReason());
    }

    private List<ClientEventBatch> eventBatches() {
        List<ClientEventBatch> batches = new ArrayList<>();
        driver.clientEvents(91L, "example:listener").subscribe(batches::add);
        return batches;
    }

    private boolean captureWithoutIdentity() {
        return driver.captureClientBehaviorEvent(1L, BehaviorEvent.Kind.ATTACK,
                BehaviorEvent.Phase.REQUESTED, BehaviorEvent.Source.LOCAL_INTENT,
                T3RBehaviorBoundaryTest::emptyFields);
    }

    /** Uses the future production overload when present while keeping this test runnable before the fix. */
    private boolean captureWithIdentity(ClientStateSnapshot.EntityIdentity identity) throws Exception {
        Method overload = Arrays.stream(RenderDriverImpl.class.getMethods())
                .filter(method -> method.getName().equals("captureClientBehaviorEvent")
                        && method.getParameterCount() == 6)
                .findFirst().orElse(null);
        if (overload == null) return captureWithoutIdentity();
        Object result = overload.invoke(driver, 1L, BehaviorEvent.Kind.ATTACK,
                BehaviorEvent.Phase.REQUESTED, BehaviorEvent.Source.LOCAL_INTENT,
                Optional.of(identity), (Supplier<EventValue.ObjectValue>) T3RBehaviorBoundaryTest::emptyFields);
        return (Boolean) result;
    }

    private static EventValue.ObjectValue emptyFields() {
        return new EventValue.ObjectValue(Map.of());
    }

    private static ClientStateSnapshot failedSnapshot(long sequence, ClientStateSnapshot.LogicalTime time) {
        throw new IllegalStateException("expected T3-R sample failure");
    }

    private static java.util.function.BiFunction<Long, ClientStateSnapshot.LogicalTime, ClientStateSnapshot>
    snapshot(ClientStateSnapshot.EntityIdentity identity) {
        return (sequence, time) -> new ClientStateSnapshot(sequence, time,
                new ClientStateSnapshot.WorldSession(1L, true, "minecraft:overworld"),
                new ClientStateSnapshot.Environment(true, "minecraft:overworld", 6000L,
                        0f, 0f, ClientStateSnapshot.BiomeSample.UNAVAILABLE),
                new ClientStateSnapshot.Player(true, identity, 0d, 64d, 0d, 0f, 0f,
                        0d, 0d, 0d, true, false, false, false, false,
                        ClientStateSnapshot.Pose.STANDING, false, 0f, 0f, false, -1,
                        ClientStateSnapshot.ItemDescription.EMPTY, ClientStateSnapshot.ItemDescription.EMPTY));
    }
}
