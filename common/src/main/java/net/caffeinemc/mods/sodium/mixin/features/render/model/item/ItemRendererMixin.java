package net.caffeinemc.mods.sodium.mixin.features.render.model.item;

import net.caffeinemc.mods.sodium.api.math.MatrixHelper;
import net.caffeinemc.mods.sodium.client.model.quad.BakedQuadView;
import net.caffeinemc.mods.sodium.client.render.immediate.model.BakedModelEncoder;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.vertex.VertexConsumerUtils;
import net.caffeinemc.mods.sodium.client.model.color.interop.ItemColorsExtension;
import net.caffeinemc.mods.sodium.client.util.DirectionUtil;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import java.util.List;

import static net.caffeinemc.mods.sodium.client.util.ModelQuadUtil.VERTEX_SIZE;

@Mixin(ItemRenderer.class)
public class ItemRendererMixin {
    @Unique
    private static final RandomSource RANDOM = new SingleThreadedRandomSource(42L);

    @Shadow
    @Final
    private ItemColors itemColors;

    // Pre-allocated vectors to avoid object creation during rendering
    @Unique
    private static final int DEFAULT_COLOR = 0xFFFFFFFF;

    @Unique
    private static ItemDisplayContext currentRenderContext = ItemDisplayContext.NONE;

    @Unique
    private static boolean isFacingAway(PoseStack.Pose matrices, BakedQuad quad) {
        // Early exit for GUI context
        if (currentRenderContext == ItemDisplayContext.GUI) {
            return false;
        }

        int[] vertices = quad.getVertices();
        if (vertices.length < 32) {
            return false;
        }

        // Fast path: Use existing normal if available
        float nx = Float.intBitsToFloat(vertices[6]);
        float ny = Float.intBitsToFloat(vertices[7]);
        float nz = Float.intBitsToFloat(vertices[14]);

        if (nx != 0 || ny != 0 || nz != 0) {
            // Use MatrixHelper for position transformation
            float x = Float.intBitsToFloat(vertices[0]);
            float y = Float.intBitsToFloat(vertices[1]);
            float z = Float.intBitsToFloat(vertices[2]);

            // Transform position using optimized helper
            float xt = MatrixHelper.transformPositionX(matrices.pose(), x, y, z);
            float yt = MatrixHelper.transformPositionY(matrices.pose(), x, y, z);
            float zt = MatrixHelper.transformPositionZ(matrices.pose(), x, y, z);

            // Transform normal using optimized helper
            float nxt = MatrixHelper.transformNormalX(matrices.normal(), nx, ny, nz);
            float nyt = MatrixHelper.transformNormalY(matrices.normal(), nx, ny, nz);
            float nzt = MatrixHelper.transformNormalZ(matrices.normal(), nx, ny, nz);

            // Direct dot product calculation
            return (nxt * -xt + nyt * -yt + nzt * -zt) < 0.0f;
        }

        // Slow path: Calculate face normal
        // Load positions and use MatrixHelper for transformations
        float x1 = Float.intBitsToFloat(vertices[0]);
        float y1 = Float.intBitsToFloat(vertices[1]);
        float z1 = Float.intBitsToFloat(vertices[2]);

        float x2 = Float.intBitsToFloat(vertices[8]);
        float y2 = Float.intBitsToFloat(vertices[9]);
        float z2 = Float.intBitsToFloat(vertices[10]);

        float x3 = Float.intBitsToFloat(vertices[16]);
        float y3 = Float.intBitsToFloat(vertices[17]);
        float z3 = Float.intBitsToFloat(vertices[18]);

        // Calculate face normal components
        float normalX = (y2 - y1) * (z3 - z1) - (z2 - z1) * (y3 - y1);
        float normalY = (z2 - z1) * (x3 - x1) - (x2 - x1) * (z3 - z1);
        float normalZ = (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1);

        // Transform normal using optimized helper
        float nxt = MatrixHelper.transformNormalX(matrices.normal(), normalX, normalY, normalZ);
        float nyt = MatrixHelper.transformNormalY(matrices.normal(), normalX, normalY, normalZ);
        float nzt = MatrixHelper.transformNormalZ(matrices.normal(), normalX, normalY, normalZ);

        // Transform first vertex position using optimized helper
        float xt = MatrixHelper.transformPositionX(matrices.pose(), x1, y1, z1);
        float yt = MatrixHelper.transformPositionY(matrices.pose(), x1, y1, z1);
        float zt = MatrixHelper.transformPositionZ(matrices.pose(), x1, y1, z1);

        // Direct dot product calculation
        return (nxt * -xt + nyt * -yt + nzt * -zt) < 0.0f;
    }


    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(ItemStack stack, ItemDisplayContext transform, boolean leftHanded, PoseStack matrices,
                               MultiBufferSource vertexConsumers, int light, int overlay, BakedModel model, CallbackInfo ci) {
        currentRenderContext = transform;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(ItemStack stack, ItemDisplayContext transform, boolean leftHanded, PoseStack matrices,
                             MultiBufferSource vertexConsumers, int light, int overlay, BakedModel model, CallbackInfo ci) {
        currentRenderContext = ItemDisplayContext.NONE;
    }

    @Inject(method = "renderModelLists", at = @At("HEAD"), cancellable = true)
    private void renderModelFast(BakedModel model, ItemStack itemStack, int light, int overlay, PoseStack matrixStack, VertexConsumer vertexConsumer, CallbackInfo ci) {
        var writer = VertexConsumerUtils.convertOrLog(vertexConsumer);
        if (writer == null) {
            return;
        }

        ci.cancel();

        PoseStack.Pose matrices = matrixStack.last();
        ItemColor colorProvider = !itemStack.isEmpty() ? ((ItemColorsExtension) this.itemColors).sodium$getColorProvider(itemStack) : null;

        // Process directional quads
        for (Direction direction : DirectionUtil.ALL_DIRECTIONS) {
            RANDOM.setSeed(42L);
            List<BakedQuad> quads = model.getQuads(null, direction, RANDOM);
            if (!quads.isEmpty()) {
                renderBakedItemQuads(matrices, writer, quads, itemStack, colorProvider, light, overlay);
            }
        }

        // Process non-directional quads
        RANDOM.setSeed(42L);
        List<BakedQuad> quads = model.getQuads(null, null, RANDOM);
        if (!quads.isEmpty()) {
            renderBakedItemQuads(matrices, writer, quads, itemStack, colorProvider, light, overlay);
        }
    }

    @Unique
    private void renderBakedItemQuads(PoseStack.Pose matrices, VertexBufferWriter writer, List<BakedQuad> quads,
                                      ItemStack itemStack, ItemColor colorProvider, int light, int overlay) {
        final int quadCount = quads.size();
        if (quadCount == 0) return;

        // Pre-fetch shouldMultiplyAlpha to avoid multiple calls
        final boolean shouldMultiplyAlpha = BakedModelEncoder.shouldMultiplyAlpha();

        // Fast path for non-colored quads
        if (colorProvider == null) {
            renderUncoloredQuads(matrices, writer, quads, quadCount, light, overlay, shouldMultiplyAlpha);
            return;
        }

        // Colored quads path
        renderColoredQuads(matrices, writer, quads, quadCount, itemStack, colorProvider, light, overlay, shouldMultiplyAlpha);
    }

    @Unique
    private void renderUncoloredQuads(PoseStack.Pose matrices, VertexBufferWriter writer, List<BakedQuad> quads,
                                      int quadCount, int light, int overlay, boolean shouldMultiplyAlpha) {
        for (int i = 0; i < quadCount; i++) {
            BakedQuad bakedQuad = quads.get(i);
            if (bakedQuad.getVertices().length < VERTEX_SIZE) continue;

            // Skip backface check if not necessary
            if (currentRenderContext == ItemDisplayContext.GUI || !isFacingAway(matrices, bakedQuad)) {
                BakedQuadView quad = (BakedQuadView) bakedQuad;
                BakedModelEncoder.writeQuadVertices(writer, matrices, quad, DEFAULT_COLOR, light, overlay, shouldMultiplyAlpha);
                SpriteUtil.markSpriteActive(quad.getSprite());
            }
        }
    }

    @Unique
    private void renderColoredQuads(PoseStack.Pose matrices, VertexBufferWriter writer, List<BakedQuad> quads,
                                    int quadCount, ItemStack itemStack, ItemColor colorProvider,
                                    int light, int overlay, boolean shouldMultiplyAlpha) {
        // Pre-cast to avoid repeated casting
        BakedQuadView quad;
        int color;

        for (int i = 0; i < quadCount; i++) {
            BakedQuad bakedQuad = quads.get(i);
            if (bakedQuad.getVertices().length < VERTEX_SIZE) continue;

            // Skip backface check if not necessary
            if (currentRenderContext == ItemDisplayContext.GUI || !isFacingAway(matrices, bakedQuad)) {
                quad = (BakedQuadView) bakedQuad;

                // Inline color calculation for better performance
                color = quad.hasColor()
                        ? ColorARGB.toABGR(colorProvider.getColor(itemStack, quad.getColorIndex()))
                        : DEFAULT_COLOR;

                BakedModelEncoder.writeQuadVertices(writer, matrices, quad, color, light, overlay, shouldMultiplyAlpha);
                SpriteUtil.markSpriteActive(quad.getSprite());
            }
        }
    }
}