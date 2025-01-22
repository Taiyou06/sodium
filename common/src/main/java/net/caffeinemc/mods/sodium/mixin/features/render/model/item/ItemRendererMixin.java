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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import java.util.List;

@Mixin(ItemRenderer.class)
public class ItemRendererMixin {
    @Unique
    private static final RandomSource RANDOM = new SingleThreadedRandomSource(42L);

    @Shadow
    @Final
    private ItemColors itemColors;

    @Unique
    private static ItemDisplayContext currentRenderContext = ItemDisplayContext.NONE;

    @Unique
    private static boolean isFacingAway(PoseStack.Pose matrices, BakedQuad quad) {
        // Early exit for GUI context
        if (currentRenderContext == ItemDisplayContext.GUI) {
            return false;
        }

        final int[] vertices = quad.getVertices();
        if (vertices.length < 32) {
            return false;
        }

        // Pre-fetch matrix references and cache commonly used values
        final Matrix4f modelViewMatrix = matrices.pose();
        final Matrix3f normalMatrix = matrices.normal();

        final float x = Float.intBitsToFloat(vertices[0]);
        final float y = Float.intBitsToFloat(vertices[1]);
        final float z = Float.intBitsToFloat(vertices[2]);

        // Transform vertex position once and cache results
        final float transformedX = MatrixHelper.transformPositionX(modelViewMatrix, x, y, z);
        final float transformedY = MatrixHelper.transformPositionY(modelViewMatrix, x, y, z);
        final float transformedZ = MatrixHelper.transformPositionZ(modelViewMatrix, x, y, z);

        // Fast path: Use existing normal if available
        // Read normal from first vertex
        float nx = Float.intBitsToFloat(vertices[6]);
        float ny = Float.intBitsToFloat(vertices[7]);
        float nz = Float.intBitsToFloat(vertices[14]);

        // Check if we have a valid normal (non-zero)
        final boolean hasValidNormal = (nx != 0.0f || ny != 0.0f || nz != 0.0f);

        if (hasValidNormal) {
            // Transform and normalize the normal vector in one pass
            final float transformedNX = MatrixHelper.transformNormalX(normalMatrix, nx, ny, nz);
            final float transformedNY = MatrixHelper.transformNormalY(normalMatrix, nx, ny, nz);
            final float transformedNZ = MatrixHelper.transformNormalZ(normalMatrix, nx, ny, nz);

            // Single dot product calculation
            return (transformedNX * -transformedX +
                    transformedNY * -transformedY +
                    transformedNZ * -transformedZ) < 0.0f;
        }

        // Slow path: Calculate face normal from three vertices
        final float x2 = Float.intBitsToFloat(vertices[8]);
        final float y2 = Float.intBitsToFloat(vertices[9]);
        final float z2 = Float.intBitsToFloat(vertices[10]);
        final float x3 = Float.intBitsToFloat(vertices[16]);
        final float y3 = Float.intBitsToFloat(vertices[17]);
        final float z3 = Float.intBitsToFloat(vertices[18]);

        // Calculate normal using cross product with minimal temporary variables
        final float normalX = (y2 - y) * (z3 - z) - (z2 - z) * (y3 - y);
        final float normalY = (z2 - z) * (x3 - x) - (x2 - x) * (z3 - z);
        final float normalZ = (x2 - x) * (y3 - y) - (y2 - y) * (x3 - x);

        // Transform the calculated normal
        final float transformedNX = MatrixHelper.transformNormalX(normalMatrix, normalX, normalY, normalZ);
        final float transformedNY = MatrixHelper.transformNormalY(normalMatrix, normalX, normalY, normalZ);
        final float transformedNZ = MatrixHelper.transformNormalZ(normalMatrix, normalX, normalY, normalZ);

        // Final dot product using already transformed position
        return (transformedNX * -transformedX +
                transformedNY * -transformedY +
                transformedNZ * -transformedZ) < 0.0f;
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
    private void renderBakedItemQuads(PoseStack.Pose matrices, VertexBufferWriter writer, List<BakedQuad> quads, ItemStack itemStack, ItemColor colorProvider, int light, int overlay) {
        final int quadCount = quads.size();
        if (quadCount == 0) return;

        final boolean shouldMultiplyAlpha = BakedModelEncoder.shouldMultiplyAlpha();
        final int defaultColor = 0xFFFFFFFF;

        if (colorProvider == null) {
            for (int i = 0; i < quadCount; i++) {
                BakedQuad bakedQuad = quads.get(i);
                if (bakedQuad.getVertices().length < 32) continue;

                if (!isFacingAway(matrices, bakedQuad)) {
                    BakedQuadView quad = (BakedQuadView) bakedQuad;
                    BakedModelEncoder.writeQuadVertices(writer, matrices, quad, defaultColor, light, overlay, shouldMultiplyAlpha);
                    SpriteUtil.markSpriteActive(quad.getSprite());
                }
            }
            return;
        }

        for (int i = 0; i < quadCount; i++) {
            BakedQuad bakedQuad = quads.get(i);
            if (bakedQuad.getVertices().length < 32) continue;

            if (!isFacingAway(matrices, bakedQuad)) {
                BakedQuadView quad = (BakedQuadView) bakedQuad;
                int color = quad.hasColor()
                        ? ColorARGB.toABGR(colorProvider.getColor(itemStack, quad.getColorIndex()))
                        : defaultColor;

                BakedModelEncoder.writeQuadVertices(writer, matrices, quad, color, light, overlay, shouldMultiplyAlpha);
                SpriteUtil.markSpriteActive(quad.getSprite());
            }
        }
    }
}