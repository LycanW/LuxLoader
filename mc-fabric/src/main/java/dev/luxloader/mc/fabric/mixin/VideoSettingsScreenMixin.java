package dev.luxloader.mc.fabric.mixin;

import static dev.luxloader.api.i18n.Messages.tr;

import dev.luxloader.mc.fabric.LuxLoaderFabricClient;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Put the pipeline selector next to Done in Video Settings. */
@Mixin(OptionsSubScreen.class)
public abstract class VideoSettingsScreenMixin {

    @Inject(method = "addFooter", at = @At("HEAD"), cancellable = true, require = 0)
    private void luxloader$videoFooter(CallbackInfo ci) {
        if (!((Object) this instanceof VideoSettingsScreen video)) {
            return;
        }
        LinearLayout buttons = LinearLayout.horizontal().spacing(8);
        buttons.addChild(Button.builder(Component.literal(tr("Rendering Pipelines")),
                b -> LuxLoaderFabricClient.openPipelineScreen(video)).width(98).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE,
                b -> video.onClose()).width(98).build());
        video.layout.addToFooter(buttons);
        ci.cancel();
    }
}
