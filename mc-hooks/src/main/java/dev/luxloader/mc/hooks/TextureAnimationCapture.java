package dev.luxloader.mc.hooks;

import dev.luxloader.api.scene.TextureAnimation;
import dev.luxloader.mc.MinecraftResourceAccess;
import java.lang.reflect.Field;
import java.util.List;

/** Version-specific private animation layout is confined to the host bridge. */
public final class TextureAnimationCapture {
    private record Fields(Field animation, Field frame, Field subFrame, Field frames,
                          Field interpolate, Field owner, Field original, Field index, Field time) { }
    private static Fields fields;
    private TextureAnimationCapture() { }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }

    public static void publish(Object state) {
        try {
            if (fields == null) {
                Class<?> type = state.getClass();
                Field info = field(type, "animationInfo");
                Class<?> animation = info.getType();
                Field owner = field(animation, "this$0");
                Class<?> frameInfo = Class.forName("net.minecraft.client.renderer.texture.SpriteContents$FrameInfo");
                fields = new Fields(info, field(type, "frame"), field(type, "subFrame"),
                        field(animation, "frames"), field(animation, "interpolateFrames"), owner,
                        field(owner.getType(), "originalImage"), field(frameInfo, "index"), field(frameInfo, "time"));
            }
            Fields f = fields;
            Object info = f.animation.get(state);
            var sprite = (net.minecraft.client.renderer.texture.SpriteContents) f.owner.get(info);
            var image = (com.mojang.blaze3d.platform.NativeImage) f.original.get(sprite);
            List<?> frames = (List<?>) f.frames.get(info);
            int index = f.frame.getInt(state);
            Object current = frames.get(index), next = frames.get((index + 1) % frames.size());
            float blend = f.interpolate.getBoolean(info)
                    ? f.subFrame.getInt(state) / (float) f.time.getInt(current) : 0;
            MinecraftResourceAccess.publishAnimation(sprite.name().toString(), new TextureAnimation(
                    image.getWidth() / sprite.width(), image.getHeight() / sprite.height(),
                    f.index.getInt(current), f.index.getInt(next), blend));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot publish host texture animation", failure);
        }
    }
}
