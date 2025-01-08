package net.caffeinemc.mods.sodium.mixin.features.render.model.item;

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
import org.joml.Matrix4f;
import org.joml.Vector3f;
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

    // Pre-allocated vectors to avoid object creation during rendering
    @Unique
    private static final Vector3f VERTEX_1 = new Vector3f();
    @Unique
    private static final Vector3f NORMAL = new Vector3f();

    @Unique
    private static ItemDisplayContext currentRenderContext = ItemDisplayContext.NONE;

    @Unique
    private static boolean isFacingAway(PoseStack.Pose matrices, BakedQuad quad) {
        if (currentRenderContext == ItemDisplayContext.GUI) {
            return false;
        }

        int[] vertices = quad.getVertices();

        // Try to use the vertex normal if it exists (faster)
        float nx = Float.intBitsToFloat(vertices[6]);  // Normal X component
        float ny = Float.intBitsToFloat(vertices[7]);  // Normal Y component
        float nz = Float.intBitsToFloat(vertices[14]); // Normal Z component

        if (nx != 0 || ny != 0 || nz != 0) {
            // Use provided normal
            NORMAL.set(nx, ny, nz);
            matrices.normal().transform(NORMAL);

            // Transform just one vertex for position
            VERTEX_1.set(
                    Float.intBitsToFloat(vertices[0]),
                    Float.intBitsToFloat(vertices[1]),
                    Float.intBitsToFloat(vertices[2])
            );
            matrices.pose().transformPosition(VERTEX_1);

            return NORMAL.dot(-VERTEX_1.x, -VERTEX_1.y, -VERTEX_1.z) < 0.0f;
        }

        // Fallback to calculating normal if vertex normal doesn't exist
        Matrix4f modelViewMatrix = matrices.pose();

        // Load just the positions we need
        float x1 = Float.intBitsToFloat(vertices[0]);
        float y1 = Float.intBitsToFloat(vertices[1]);
        float z1 = Float.intBitsToFloat(vertices[2]);

        float x2 = Float.intBitsToFloat(vertices[8]);
        float y2 = Float.intBitsToFloat(vertices[9]);
        float z2 = Float.intBitsToFloat(vertices[10]);

        float x3 = Float.intBitsToFloat(vertices[16]);
        float y3 = Float.intBitsToFloat(vertices[17]);
        float z3 = Float.intBitsToFloat(vertices[18]);

        // Calculate two edges directly
        float edge1x = x2 - x1;
        float edge1y = y2 - y1;
        float edge1z = z2 - z1;

        float edge2x = x3 - x1;
        float edge2y = y3 - y1;
        float edge2z = z3 - z1;

        // Cross product to get normal
        NORMAL.set(
                edge1y * edge2z - edge1z * edge2y,
                edge1z * edge2x - edge1x * edge2z,
                edge1x * edge2y - edge1y * edge2x
        );

        // Transform normal and position
        matrices.normal().transform(NORMAL);
        VERTEX_1.set(x1, y1, z1);
        modelViewMatrix.transformPosition(VERTEX_1);

        return NORMAL.dot(-VERTEX_1.x, -VERTEX_1.y, -VERTEX_1.z) < 0.0f;
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