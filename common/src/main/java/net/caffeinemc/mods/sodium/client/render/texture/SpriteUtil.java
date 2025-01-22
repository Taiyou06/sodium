package net.caffeinemc.mods.sodium.client.render.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;

public class SpriteUtil {
    private static final Object2BooleanMap<TextureAtlasSprite> spriteStateCache = new Object2BooleanOpenHashMap<>();

    public static void markSpriteActive(@Nullable TextureAtlasSprite sprite) {
        if (sprite == null) {
            // Can happen in some cases, for example if a mod passes a BakedQuad with a null sprite
            // to a VertexConsumer that does not have a texture element.
            return;
        }

        // Check if the sprite's state is already cached
        boolean currentState = spriteStateCache.getOrDefault(sprite, false);
        boolean newState = true;

        if (currentState != newState) {
            ((SpriteContentsExtension) sprite.contents()).sodium$setActive(newState);
            spriteStateCache.put(sprite, newState);
        }
    }

    public static boolean hasAnimation(TextureAtlasSprite sprite) {
        return ((SpriteContentsExtension) sprite.contents()).sodium$hasAnimation();
    }
}