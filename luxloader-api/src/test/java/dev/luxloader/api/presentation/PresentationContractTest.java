package dev.luxloader.api.presentation;

import dev.luxloader.api.GpuId;
import dev.luxloader.api.event.EventValue;
import dev.luxloader.api.host.HostSoundBackend;
import dev.luxloader.api.resource.ResourceKey;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PresentationContractTest {
    @Test void preparedAssetCopiesInputAndOnlyExposesReadOnlyIndependentViews() {
        byte[] bytes = {'O', 'g', 'g', 'S', 7};
        var asset = new SoundAsset.PreparedOgg(new ResourceKey("test", "voice"), 4, ByteBuffer.wrap(bytes));
        bytes[4] = 99;
        var first = asset.encoded(); first.position(4);
        assertEquals(7, first.get());
        assertEquals(0, asset.encoded().position());
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> asset.encoded().put(0, (byte)0));
        assertEquals(4, asset.revision());
    }

    @Test void assetAddressingRejectsPathsAndOversizedOrUnrecognizedContainers() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceKey("test", "C:/voice.ogg"));
        assertThrows(IllegalArgumentException.class, () -> new SoundAsset.HostOgg(new ResourceKey("test", "voice.ogg"), false));
        assertThrows(IllegalArgumentException.class, () -> new SoundAsset.PreparedOgg(new ResourceKey("test", "voice"), 0, ByteBuffer.wrap(new byte[4])));
        assertThrows(IllegalArgumentException.class, () -> new SoundAsset.PreparedOgg(new ResourceKey("test", "voice"), 0, ByteBuffer.allocate(SoundAsset.PreparedOgg.MAX_ENCODED_BYTES + 1)));
        assertDoesNotThrow(() -> new SoundAsset.HostOgg(new ResourceKey("test", "sounds/voice.ogg"), true));
    }

    @Test void capabilitiesAndCustomParametersAreImmutable() {
        var type = new GpuId("test", "voice");
        var supported = new HashSet<>(Set.of(type));
        var capabilities = new PresentationService.Capabilities(true, supported, HostSoundBackend.Capabilities.NONE);
        supported.clear(); assertEquals(Set.of(type), capabilities.visualTypes());
        assertThrows(UnsupportedOperationException.class, () -> capabilities.visualTypes().clear());
        var map = new java.util.HashMap<String, EventValue>(); map.put("speed", new EventValue.DecimalNumber(2));
        var parameters = new PresentationService.Parameters(1, 2, 3, 1, 0.5f, 1, false, new EventValue.ObjectValue(map));
        map.clear(); assertEquals(1, parameters.custom().values().size());
    }

    @Test void parametersAndRequestsDoNotAcceptNonfiniteControlsOrInvalidLifetimes() {
        assertThrows(IllegalArgumentException.class, () -> PresentationService.Parameters.at(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PresentationService.Parameters(0, 0, 0, 1, 2, 1, false, EventValue.ObjectValue.empty()));
        assertThrows(IllegalArgumentException.class, () -> new PresentationService.Request(new dev.luxloader.api.state.ClientStateSnapshot.WorldSession(1, true, "test"), 0, 0, PresentationService.Parameters.at(0, 0, 0)));
        assertThrows(UnsupportedOperationException.class, () -> PresentationService.UNAVAILABLE.register(new PresentationService.Definition(new GpuId("test", "sound"), null,
                new SoundAsset.HostEvent(new ResourceKey("test", "voice")), PresentationService.Category.PLAYERS, false)));
    }
}
