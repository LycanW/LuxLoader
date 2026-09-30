package dev.luxloader.mc.hooks;

import com.mojang.blaze3d.audio.Channel;
import java.util.function.Consumer;

/** Executes on the host audio executor, including explicit disposal when its channel has retired. */
public interface ManagedSoundChannel {
    void luxloader$consumeManaged(Consumer<Channel> available, Runnable retired);
}
