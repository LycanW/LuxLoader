package dev.luxloader.mc.hooks.mixin;

import dev.luxloader.api.i18n.Messages;
import net.minecraft.client.resources.language.LanguageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep UI and diagnostic messages in sync with the host's selected language. */
@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin {
    @Shadow public abstract String getSelected();

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void luxloader$initializeLanguage(CallbackInfo ci) {
        Messages.setLanguage(getSelected());
    }

    @Inject(method = "setSelected", at = @At("RETURN"), require = 1)
    private void luxloader$selectLanguage(String language, CallbackInfo ci) {
        Messages.setLanguage(language);
    }
}
