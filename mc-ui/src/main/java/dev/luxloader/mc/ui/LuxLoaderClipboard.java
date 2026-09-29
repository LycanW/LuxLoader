package dev.luxloader.mc.ui;

import static dev.luxloader.api.i18n.Messages.tr;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLError;

/** SDL's CharSequence overload uses the small thread-local MemoryStack. */
final class LuxLoaderClipboard {
    private LuxLoaderClipboard() { }
    static void copy(String text) {
        copy(text, utf8 -> {
            if (!SDLClipboard.SDL_SetClipboardText(utf8))
                throw new IllegalStateException(tr("Clipboard write failed: ") + SDLError.SDL_GetError());
        });
    }

    static void copy(String text, java.util.function.Consumer<java.nio.ByteBuffer> writer) {
        var utf8 = MemoryUtil.memUTF8(text);
        try {
            writer.accept(utf8);
        } finally {
            MemoryUtil.memFree(utf8);
        }
    }
}
