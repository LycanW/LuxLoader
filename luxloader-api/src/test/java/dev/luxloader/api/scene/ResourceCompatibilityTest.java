package dev.luxloader.api.scene;

import dev.luxloader.api.resource.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ResourceCompatibilityTest {
    @Test void legacyReadRevisionAndAnimationStillWorkAndOpenCapturesCallerOwnedBytes() throws Exception {
        ResourceAccess legacy = new ResourceAccess() {
            public long revision() { return 7; }
            public Optional<byte[]> read(String ns, String path) { return Optional.of(new byte[]{9}); }
        };
        assertEquals(new ResourceState(7,ResourceState.Phase.READY),legacy.state());
        assertEquals(TextureAnimation.STATIC,legacy.animation(new TextureRef("pack:block",1,1)));
        try (var input=legacy.open(new ResourceKey("pack","textures/block.png")).orElseThrow()) { assertEquals(9,input.read()); }
        assertTrue(ResourceAccess.EMPTY.open(new ResourceKey("pack","missing")).isEmpty());
    }
    @Test void invalidOrAmbiguousPackPathsAreRejectedWithoutChangingCaseOrResolvingTraversal() {
        assertEquals("pack:textures/block.png",new ResourceKey("pack","textures/block.png").toString());
        for(String path:new String[]{"/root","trailing/","double//slash","../escape","nested/./file","nested/../file","Upper.png",""})
            assertThrows(IllegalArgumentException.class,()->new ResourceKey("pack",path));
        assertThrows(IllegalArgumentException.class,()->new ResourceKey("Pack","file"));
    }
}
