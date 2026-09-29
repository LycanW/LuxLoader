package dev.luxloader.core.diag;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsPeakTest {
    @Test void completedSpikeSurvivesLaterFastFramesAndScopedMetrics() {
        var diagnostics = new DiagnosticsImpl(Path.of("build/diagnostics"), "", false);
        var child = diagnostics.scoped("plugin");
        child.metric("cpu.terrain", 450, "ms");
        child.metric("cpu.terrain", .05, "ms");
        child.metric("cpu.terrain", Double.NaN, "ms");
        String report = diagnostics.exportReport();
        assertTrue(report.contains("cpu.terrain.peak = 450.000 ms"));
        assertFalse(report.contains("peak = NaN"));
    }
}
