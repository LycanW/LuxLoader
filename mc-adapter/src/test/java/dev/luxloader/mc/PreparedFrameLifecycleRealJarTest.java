package dev.luxloader.mc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the actual client's frame lease guard and release without creating a game or GPU device. */
class PreparedFrameLifecycleRealJarTest {
    @Test void secondaryFrameMustCloseBeforePreparingPrimaryAndNextFrame() throws Exception {
        var jar = MinecraftClientJar.resolve();
        assumeTrue(jar != null);
        try (var loader = MinecraftClientJar.classLoaderFor(jar)) {
            Class<?> dispatcherType = loader.loadClass("net.minecraft.client.renderer.feature.FeatureRenderDispatcher");
            Class<?> frameType = loader.loadClass("net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedFrame");
            Class<?> contextType = loader.loadClass("net.minecraft.client.renderer.feature.FeatureFrameContext");
            Class<?> storageType = loader.loadClass("net.minecraft.client.renderer.SubmitNodeStorage");
            Class<?> stagedType = loader.loadClass("net.minecraft.client.renderer.StagedVertexBuffer");
            Class<?> rendererMapType = loader.loadClass("net.minecraft.client.renderer.feature.FeatureRendererMap");

            // Bypass constructors that allocate GPU/native buffers. Only empty CPU
            // collections are needed by the real begin/close lifecycle under test.
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            Object dispatcher = unsafe.allocateInstance(dispatcherType);
            Object staged = unsafe.allocateInstance(stagedType);
            var draws = new ArrayList<>();
            set(staged, "draws", draws);
            set(dispatcher, "stagedVertexBuffer", staged);
            set(dispatcher, "featureRenderers", rendererMapType.getConstructor().newInstance());
            Object context = contextType.getConstructors()[0].newInstance(
                    null, null, null, null, null, null, null, staged);
            Object secondary = storageType.getConstructor().newInstance();
            Object primary = storageType.getConstructor().newInstance();
            Object frame = frameType.getConstructor(dispatcherType).newInstance(dispatcher);
            var begin = frameType.getDeclaredMethod("begin", contextType, storageType);
            begin.setAccessible(true);
            var close = frameType.getMethod("close");
            var contextField = frameType.getDeclaredField("context");
            contextField.setAccessible(true);

            for (int iteration = 0; iteration < 3; iteration++) {
                assertSame(frame, begin.invoke(frame, context, secondary));
                var failure = assertThrows(InvocationTargetException.class,
                        () -> begin.invoke(frame, context, primary));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertEquals("PreparedFrame already in use", failure.getCause().getMessage());
                draws.add(new Object());
                close.invoke(frame);
                assertNull(contextField.get(frame));
                assertTrue(draws.isEmpty(), "Supplemental draw references must be released");

                assertSame(frame, begin.invoke(frame, context, primary));
                assertSame(context, contextField.get(frame));
                close.invoke(frame);
                assertNull(contextField.get(frame));
            }
        }
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
