package dev.luxloader.mc.ui;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.api.plugin.RenderDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Consume failures after the frame boundary; keep full stacks in Copy Info. */
public final class LuxLoaderNotifications {
    private LuxLoaderNotifications() { }

    public static void showPending(RenderDriver driver) {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.gui == null) return;
        for (var next = driver.pollPipelineFailure(); next.isPresent(); next = driver.pollPipelineFailure()) {
            var failure = next.orElseThrow();
            String summary = failure.detail().lines().filter(line -> !line.isBlank()).findFirst().orElse(failure.reason());
            if (summary.length() > 160) summary = summary.substring(0, 157) + "…";
            var message = Component.literal(tr("[LuxLoader] Pipeline failed and was disabled: ") + failure.pipeline()
                    + "。" + summary + tr(". Open Video Settings > Rendering Pipelines, select the plugin and choose Copy Error to inspect details and retry."));
            minecraft.gui.chatListener().handleSystemMessage(message, false);
            minecraft.gui.hud.setOverlayMessage(Component.literal(tr("LuxLoader pipeline disabled after a failure; see chat for details")), false);
        }
    }
}
