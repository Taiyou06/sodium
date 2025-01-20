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
import org.joml.Matrix3f;
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
    private static final Vector3f VERTEX_POS = new Vector3f();
    @Unique
    private static final Vector3f NORMAL_VEC = new Vector3f();
    @Unique
    private static final float[] VERTEX_DATA = new float[3];

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

        // Pre-fetch matrix references
        Matrix4f modelViewMatrix = matrices.pose();
        Matrix3f normalMatrix = matrices.normal();

        // Fast path: Use existing normal if available
        float nx = Float.intBitsToFloat(vertices[6]);
        float ny = Float.intBitsToFloat(vertices[7]);
        float nz = Float.intBitsToFloat(vertices[14]);

        if (nx != 0 || ny != 0 || nz != 0) {
            // Load position directly into array for better locality
            VERTEX_DATA[0] = Float.intBitsToFloat(vertices[0]);
            VERTEX_DATA[1] = Float.intBitsToFloat(vertices[1]);
            VERTEX_DATA[2] = Float.intBitsToFloat(vertices[2]);

            VERTEX_POS.set(VERTEX_DATA[0], VERTEX_DATA[1], VERTEX_DATA[2]);
            modelViewMatrix.transformPosition(VERTEX_POS);

            NORMAL_VEC.set(nx, ny, nz);
            normalMatrix.transform(NORMAL_VEC);

            // Single dot product with negated vertex position
            return NORMAL_VEC.dot(-VERTEX_POS.x, -VERTEX_POS.y, -VERTEX_POS.z) < 0.0f;
        }

        // Slow path: Calculate face normal
        // Load positions directly into working array
        VERTEX_DATA[0] = Float.intBitsToFloat(vertices[0]);
        VERTEX_DATA[1] = Float.intBitsToFloat(vertices[1]);
        VERTEX_DATA[2] = Float.intBitsToFloat(vertices[2]);
        float x1 = VERTEX_DATA[0], y1 = VERTEX_DATA[1], z1 = VERTEX_DATA[2];

        VERTEX_DATA[0] = Float.intBitsToFloat(vertices[8]);
        VERTEX_DATA[1] = Float.intBitsToFloat(vertices[9]);
        VERTEX_DATA[2] = Float.intBitsToFloat(vertices[10]);
        float x2 = VERTEX_DATA[0], y2 = VERTEX_DATA[1], z2 = VERTEX_DATA[2];

        VERTEX_DATA[0] = Float.intBitsToFloat(vertices[16]);
        VERTEX_DATA[1] = Float.intBitsToFloat(vertices[17]);
        VERTEX_DATA[2] = Float.intBitsToFloat(vertices[18]);
        float x3 = VERTEX_DATA[0], y3 = VERTEX_DATA[1], z3 = VERTEX_DATA[2];

        // Calculate edges and cross product in single step
        NORMAL_VEC.set(
                (y2 - y1) * (z3 - z1) - (z2 - z1) * (y3 - y1),
                (z2 - z1) * (x3 - x1) - (x2 - x1) * (z3 - z1),
                (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1)
        );

        normalMatrix.transform(NORMAL_VEC);
        VERTEX_POS.set(x1, y1, z1);
        modelViewMatrix.transformPosition(VERTEX_POS);

        return NORMAL_VEC.dot(-VERTEX_POS.x, -VERTEX_POS.y, -VERTEX_POS.z) < 0.0f;
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