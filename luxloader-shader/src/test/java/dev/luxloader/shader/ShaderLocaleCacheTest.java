package dev.luxloader.shader;

import dev.luxloader.api.i18n.Messages;
import dev.luxloader.api.vulkan.ComputeFillModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ShaderLocaleCacheTest {
    @TempDir Path temporary;

    @Test
    void languageChangesPreserveCacheHits() throws Exception {
        String previous = Messages.language();
        AtomicInteger compilations = new AtomicInteger();
        ShaderCompiler compiler = new ShaderCompiler() {
            public boolean available() { return true; }
            public String describe() { return "Compiler " + Messages.language(); }
            public String cacheIdentity() { return "test-compiler-v1"; }
            public ShaderCompileResult compile(ShaderSource source, List<Path> includes) {
                compilations.incrementAndGet();
                return ShaderCompileResult.ok(source.name(), source.stage(), source.entryPoint(),
                        ComputeFillModule.bytes(), List.of(), 1, false, describe());
            }
        };
        try (ShaderLibrary library = ShaderLibrary.builder().compiler(compiler).cacheRoot(temporary).build()) {
            ShaderSource source = ShaderSource.FromSource.of("locale-cache", ShaderStage.COMPUTE, "// fixture");
            Messages.setLanguage("zh");
            assertTrue(library.compile(source).get(10, TimeUnit.SECONDS).success());
            Messages.setLanguage("en");
            assertTrue(library.peekCached(source).isPresent());
            assertTrue(library.compile(source).get(10, TimeUnit.SECONDS).success());
            assertEquals(1, compilations.get());
        } finally {
            Messages.setLanguage(previous);
        }
    }
}
