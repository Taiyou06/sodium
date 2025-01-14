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
    private static final Vector3f FACE_NORMAL = new Vector3f();
    @Unique
    private static final Vector3f VERTEX_POS = new Vector3f();
    @Unique
    private static final Matrix4f FACE_NORMAL_MATRIX = new Matrix4f();

    // New pre-allocated vectors for edge calculations
    @Unique
    private static final Vector3f EDGE1 = new Vector3f();
    @Unique
    private static final Vector3f EDGE2 = new Vector3f();

    // Cache for vertex coordinates
    @Unique
    private static final float[] VERTEX_CACHE = new float[24]; // 8 floats per vertex, 3 vertices needed

    @Unique
    private static ItemDisplayContext currentRenderContext = ItemDisplayContext.NONE;

    @Unique
    private static boolean isFacingAway(PoseStack.Pose matrices, BakedQuad quad) {
        if (currentRenderContext == ItemDisplayContext.GUI) {
            return false;
        }

        int[] vertices = quad.getVertices();
        if (vertices.length < 32) {
            return false;
        }

        // Extract normal directly from first vertex - most common case
        float nx = Float.intBitsToFloat(vertices[6]);
        float ny = Float.intBitsToFloat(vertices[7]);
        float nz = Float.intBitsToFloat(vertices[14]);

        // Fast path for quads with normals
        if (nx != 0 || ny != 0 || nz != 0) {
            matrices.pose().normal(FACE_NORMAL_MATRIX);

            // Transform normal using matrix multiplication
            FACE_NORMAL.set(
                    nx * FACE_NORMAL_MATRIX.m00() + ny * FACE_NORMAL_MATRIX.m01() + nz * FACE_NORMAL_MATRIX.m02(),
                    nx * FACE_NORMAL_MATRIX.m10() + ny * FACE_NORMAL_MATRIX.m11() + nz * FACE_NORMAL_MATRIX.m12(),
                    nx * FACE_NORMAL_MATRIX.m20() + ny * FACE_NORMAL_MATRIX.m21() + nz * FACE_NORMAL_MATRIX.m22()
            );

            // Transform first vertex position
            VERTEX_POS.set(
                    Float.intBitsToFloat(vertices[0]),
                    Float.intBitsToFloat(vertices[1]),
                    Float.intBitsToFloat(vertices[2])
            );
            matrices.pose().transformPosition(VERTEX_POS);

            // Optimized dot product calculation
            return (FACE_NORMAL.x * -VERTEX_POS.x +
                    FACE_NORMAL.y * -VERTEX_POS.y +
                    FACE_NORMAL.z * -VERTEX_POS.z) < 0.0f;
        }

        // Fallback path for quads without normals - using cached arrays
        // Cache vertex coordinates to avoid repeated float conversions
        for (int i = 0; i < 24; i += 8) {
            int baseIndex = (i / 8) * 8;
            VERTEX_CACHE[i] = Float.intBitsToFloat(vertices[baseIndex]);     // x
            VERTEX_CACHE[i + 1] = Float.intBitsToFloat(vertices[baseIndex + 1]); // y
            VERTEX_CACHE[i + 2] = Float.intBitsToFloat(vertices[baseIndex + 2]); // z
        }

        // Calculate edges using pre-allocated vectors
        EDGE1.set(
                VERTEX_CACHE[8] - VERTEX_CACHE[0],
                VERTEX_CACHE[9] - VERTEX_CACHE[1],
                VERTEX_CACHE[10] - VERTEX_CACHE[2]
        );

        EDGE2.set(
                VERTEX_CACHE[16] - VERTEX_CACHE[0],
                VERTEX_CACHE[17] - VERTEX_CACHE[1],
                VERTEX_CACHE[18] - VERTEX_CACHE[2]
        );

        // Calculate cross product directly into FACE_NORMAL
        FACE_NORMAL.set(
                EDGE1.y * EDGE2.z - EDGE1.z * EDGE2.y,
                EDGE1.z * EDGE2.x - EDGE1.x * EDGE2.z,
                EDGE1.x * EDGE2.y - EDGE1.y * EDGE2.x
        );

        matrices.pose().normal(FACE_NORMAL_MATRIX);
        FACE_NORMAL_MATRIX.transformDirection(FACE_NORMAL);

        // Transform first vertex
        VERTEX_POS.set(VERTEX_CACHE[0], VERTEX_CACHE[1], VERTEX_CACHE[2]);
        matrices.pose().transformPosition(VERTEX_POS);

        // Optimized dot product calculation
        return (FACE_NORMAL.x * -VERTEX_POS.x +
                FACE_NORMAL.y * -VERTEX_POS.y +
                FACE_NORMAL.z * -VERTEX_POS.z) < 0.0f;
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