package dev.luxloader.mc.hooks;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;
import java.nio.file.*;

/** Shared bootstrap for offline resource and audio transforms in the same test JVM. */
final class OfflineMixinHarness {
    private static IMixinTransformer transformer;
    private static MixinEnvironment environment;
    private static OfflineMixinService service;

    static synchronized byte[] transform(String name, Path output) throws Exception {
        if (transformer == null) {
            System.setProperty("mixin.service", OfflineMixinService.class.getName());
            MixinBootstrap.init();
            environment = MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
            service = (OfflineMixinService)MixinService.getService();
            transformer = service.transformer(); environment.setActiveTransformer(transformer);
            com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
            Mixins.addConfiguration("luxloader.r1-test.mixins.json");
            Mixins.addConfiguration("luxloader.t5-test.mixins.json");
        }
        byte[] original;
        try (var input = service.getResourceAsStream(name.replace('.', '/') + ".class")) { original = input.readAllBytes(); }
        var transformed = transformer.transformClass(environment, name, original);
        org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(original, transformed), name);
        var destination = output.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(destination.getParent()); Files.write(destination, transformed);
        return transformed;
    }
}
