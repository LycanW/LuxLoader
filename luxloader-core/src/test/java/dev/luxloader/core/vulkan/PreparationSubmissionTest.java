package dev.luxloader.core.vulkan;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

class PreparationSubmissionTest {
    @Test void publicationRequiresSuccessfulSubmitAndSuccessfulCompletionInThatOrder() {
        var steps = new ArrayList<String>();
        PreparationSubmission.complete(() -> { steps.add("submit"); return VK_SUCCESS; },
                () -> { steps.add("wait"); return VK_SUCCESS; }, () -> steps.add("release"),
                () -> fail("Completed batch must not be quarantined"));
        assertEquals(List.of("submit", "wait", "release"), steps);
    }

    @Test void exceptionBeforeNativeSubmitNeverEntersTheNormallyWaitableQueue() {
        var quarantined = new ArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> PreparationSubmission.complete(
                () -> { throw new IllegalArgumentException("Before native submission"); },
                () -> { fail("An unsignaled fence must never be awaited"); return VK_SUCCESS; },
                () -> fail("Unconfirmed native state must not be freed"), () -> quarantined.add("batch")));
        assertEquals(List.of("batch"), quarantined);
        // A later healthy batch needs no wait on the quarantined batch's unsignaled fence.
        PreparationSubmission.complete(() -> VK_SUCCESS, () -> VK_SUCCESS, () -> {}, () -> fail());
        assertEquals(1, quarantined.size());
    }

    @Test void submitFailureAndDeviceLossNeverReportReadyOrWaitAnUnsubmittedFence() {
        for (int result : new int[]{VK_ERROR_DEVICE_LOST, VK_ERROR_INITIALIZATION_FAILED}) {
            var actions = new ArrayList<String>();
            assertThrows(IllegalStateException.class, () -> PreparationSubmission.complete(() -> result,
                    () -> { fail("Submission failed"); return VK_SUCCESS; },
                    () -> actions.add("release"), () -> actions.add("quarantine")));
            assertEquals(List.of("quarantine"), actions);
        }
        assertThrows(IllegalStateException.class, () -> PreparationSubmission.complete(() -> VK_SUCCESS,
                () -> VK_ERROR_DEVICE_LOST, () -> fail(), () -> {}));
    }

    @Test void outOfMemoryCanReleaseAnUnaffectedBatchWithoutClaimingPublication() {
        var actions = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> PreparationSubmission.complete(() -> VK_ERROR_OUT_OF_DEVICE_MEMORY,
                () -> { fail(); return VK_SUCCESS; }, () -> actions.add("release"), () -> fail()));
        assertEquals(List.of("release"), actions);
    }
}
