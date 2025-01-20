package net.caffeinemc.mods.sodium.client.render.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

public class SpriteUtil {
    private static final long UPDATE_INTERVAL = 50L; // 20 times per second (1000ms / 20)
    private static final Object2LongOpenHashMap<TextureAtlasSprite> lastUpdateTimes = new Object2LongOpenHashMap<>();

    static {
        lastUpdateTimes.defaultReturnValue(-1L);
    }

    public static void markSpriteActive(@Nullable TextureAtlasSprite sprite) {
        if (sprite == null) {
            // Can happen in some cases, for example if a mod passes a BakedQuad with a null sprite
            // to a VertexConsumer that does not have a texture element.
            return;
        }

        long currentTime = System.nanoTime() / 1_000_000L; // Convert to milliseconds
        long lastUpdate = lastUpdateTimes.getLong(sprite);

        if (currentTime - lastUpdate >= UPDATE_INTERVAL) {
            ((SpriteContentsExtension) sprite.contents()).sodium$setActive(true);
            lastUpdateTimes.put(sprite, currentTime);

            // Clean up old entries periodically
            if (lastUpdateTimes.size() > 1000) { // Arbitrary threshold
                long threshold = currentTime - UPDATE_INTERVAL * 2;
                lastUpdateTimes.object2LongEntrySet().removeIf(
                        entry -> entry.getLongValue() < threshold
                );
            }
        }
    }

    public static boolean hasAnimation(TextureAtlasSprite sprite) {
        return ((SpriteContentsExtension) sprite.contents()).sodium$hasAnimation();
    }
}