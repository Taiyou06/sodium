package net.caffeinemc.mods.sodium.mixin.features.render.model.item;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.api.math.MatrixHelper;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex;
import net.caffeinemc.mods.sodium.client.model.quad.BakedQuadView;
import net.caffeinemc.mods.sodium.client.render.immediate.model.BakedModelEncoder;
import net.caffeinemc.mods.sodium.client.render.vertex.VertexConsumerUtils;
import net.caffeinemc.mods.sodium.client.util.DirectionUtil;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Unique
    private static final ThreadLocal<RandomSource> random = ThreadLocal.withInitial(() -> new SingleThreadedRandomSource(42L));

    @Shadow
    private static int getLayerColorSafe(int[] is, int i) {
        throw new AssertionError("Not shadowed");
    }

    /**
     * @reason Avoid Allocations
     * @return JellySquid
     */
    @WrapOperation(method = "renderItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderQuadList(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Ljava/util/List;[III)V"))
    private static void renderModelFast(PoseStack poseStack, VertexConsumer vertexConsumer, List<BakedQuad> quads, int[] colors, int light, int overlay, Operation<Void> original) {
        var writer = VertexConsumerUtils.convertOrLog(vertexConsumer);

        if (writer == null) {
            original.call(poseStack, vertexConsumer, quads, colors, light, overlay);
            return;
        }

        // TODO/NOTE: Should .last be a LocalRef?
        if (!quads.isEmpty()) {
            renderBakedItemQuads(poseStack.last(), writer, quads, colors, light, overlay);
        }
    }

    @Unique
    @SuppressWarnings("ForLoopReplaceableByForEach")
    private static void renderBakedItemQuads(PoseStack.Pose matrices, VertexBufferWriter writer,
                                             List<BakedQuad> quads, int[] colors, int light, int overlay) {
        // Cache frequently accessed values to reduce indirection
        final int quadCount = quads.size();
        if (quadCount == 0) return;

        final boolean multiplyAlpha = BakedModelEncoder.shouldMultiplyAlpha();
        final Matrix3f matNormal = matrices.normal();
        final Matrix4f matPosition = matrices.pose();
        final boolean trustedNormals = matrices.trustedNormals;

        // Process quads in batches to amortize MemoryStack overhead
        final int batchSize = Math.min(quadCount, 16);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long batchBuffer = stack.nmalloc(batchSize * 4 * EntityVertex.STRIDE);
            long ptr = batchBuffer;
            int batchedQuads = 0;

            for (int i = 0; i < quadCount; i++) {
                BakedQuad bakedQuad = quads.get(i);

                // Fast-path skip for invalid quads
                if (bakedQuad.vertices().length < 32) continue;

                // Single cast for the quad view
                BakedQuadView quad = (BakedQuadView) (Object) bakedQuad;

                // Resolve tint color once per quad
                int quadColor = 0xFFFFFFFF;
                if (bakedQuad.isTinted()) {
                    int tintIndex = bakedQuad.tintIndex();
                    // Bounds check without method call overhead
                    if (tintIndex >= 0 && tintIndex < colors.length) {
                        quadColor = ColorARGB.toABGR(colors[tintIndex]);
                    }
                }

                // Inline writeQuadVertices logic for direct batching
                for (int vi = 0; vi < 4; vi++) {
                    final float x = quad.getX(vi);
                    final float y = quad.getY(vi);
                    final float z = quad.getZ(vi);

                    // Inlined mergeLighting to avoid method call
                    final int maxLight = quad.getMaxLightQuad(vi);
                    final int newLight = (maxLight == 0) ? light :
                            (Math.max(maxLight & 0xFFFF, light & 0xFFFF) |
                                    (Math.max((maxLight >> 16) & 0xFFFF, (light >> 16) & 0xFFFF) << 16));

                    int finalColor = quadColor;
                    if (multiplyAlpha) {
                        finalColor = ColorMixer.mulComponentWise(finalColor, quad.getColor(vi));
                    }

                    final int normal = MatrixHelper.transformNormal(matNormal, trustedNormals, quad.getAccurateNormal(vi));
                    final float xt = MatrixHelper.transformPositionX(matPosition, x, y, z);
                    final float yt = MatrixHelper.transformPositionY(matPosition, x, y, z);
                    final float zt = MatrixHelper.transformPositionZ(matPosition, x, y, z);

                    EntityVertex.write(ptr, xt, yt, zt, finalColor, quad.getTexU(vi), quad.getTexV(vi),
                            overlay, newLight, normal);
                    ptr += EntityVertex.STRIDE;
                }

                batchedQuads++;

                // Flush batch when full
                if (batchedQuads == batchSize) {
                    writer.push(stack, batchBuffer, batchedQuads * 4, EntityVertex.FORMAT);
                    batchedQuads = 0;
                    ptr = batchBuffer;
                }

                // Mark sprite active (moved outside inner loop)
                var sprite = quad.getSprite();
                if (sprite != null) {
                    SpriteUtil.INSTANCE.markSpriteActive(sprite);
                }
            }

            // Flush final batch
            if (batchedQuads > 0) {
                writer.push(stack, batchBuffer, batchedQuads * 4, EntityVertex.FORMAT);
            }
        }
    }
}
