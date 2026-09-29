package dev.luxloader.core.vulkan;

import java.util.function.IntSupplier;
import static dev.luxloader.api.i18n.Messages.tr;
import static org.lwjgl.vulkan.VK10.*;

/** Publication gate. Unconfirmed submissions are retained separately from normally waitable batches. */
final class PreparationSubmission {
    private PreparationSubmission() { }

    static void complete(IntSupplier submit, IntSupplier await, Runnable release, Runnable quarantine) {
        int submitted;
        try { submitted = submit.getAsInt(); }
        catch (RuntimeException error) { quarantine.run(); throw error; }
        if (submitted != VK_SUCCESS) {
            if (submitted == VK_ERROR_OUT_OF_HOST_MEMORY || submitted == VK_ERROR_OUT_OF_DEVICE_MEMORY) release.run();
            else quarantine.run();
            throw new IllegalStateException(tr("Preparation submission failed: ") + VulkanDevice.resultName(submitted));
        }
        int completed;
        try { completed = await.getAsInt(); }
        catch (RuntimeException error) { quarantine.run(); throw error; }
        if (completed != VK_SUCCESS) {
            quarantine.run();
            throw new IllegalStateException(tr("Preparation completion failed: ") + VulkanDevice.resultName(completed));
        }
        release.run();
    }
}
