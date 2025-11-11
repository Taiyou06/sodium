package net.caffeinemc.mods.sodium.mixin.core.render.frustum;

import org.joml.FrustumIntersection;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(FrustumIntersection.class)
public class MixinFrustumIntersection {

    @Shadow
    private Vector4f[] planes;

    private static final int INSIDE = -2;
    private static final int INTERSECT = -1;

    /**
     * @reason Optimize frustum-AABB intersection by replacing deeply nested conditionals
     * with a linear loop. Reduces branch mispredictions and bytecode size while preserving
     * exact behavior and return values (-2 inside, -1 intersect, 0-5 outside plane index).
     */
    @Overwrite
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return intersectAabMasked(minX, minY, minZ, maxX, maxY, maxZ, 0);
    }

    /**
     * @reason Optimize masked frustum-AABB intersection using loop-based approach.
     * Mask bits: 1=nx, 2=px, 4=ny, 8=py, 16=nz, 32=pz
     */
    @Overwrite
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int mask) {
        return intersectAabMasked(minX, minY, minZ, maxX, maxY, maxZ, mask);
    }

    /**
     * @reason Optimize masked frustum-AABB intersection with startPlane optimization.
     * First checks if startPlane (when masked) causes immediate rejection.
     */
    @Overwrite
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int mask, int startPlane) {
        // If startPlane is masked but box is outside it, return immediately
        if ((mask & (1 << startPlane)) != 0) {
            Vector4f p = this.planes[startPlane];
            final float px = p.x < 0.0f ? minX : maxX;
            final float py = p.y < 0.0f ? minY : maxY;
            final float pz = p.z < 0.0f ? minZ : maxZ;

            if (p.x * px + p.y * py + p.z * pz + p.w < 0.0f) {
                return startPlane;
            }
        }

        return intersectAabMasked(minX, minY, minZ, maxX, maxY, maxZ, mask);
    }

    /**
     * Core optimized intersection logic shared by all variants.
     * Tests all non-masked planes sequentially with early exit.
     */
    private int intersectAabMasked(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int mask) {
        boolean allInside = true;

        for (int i = 0; i < 6; i++) {
            if ((mask & (1 << i)) != 0) continue; // Skip masked planes

            Vector4f plane = this.planes[i];
            final float nx = plane.x, ny = plane.y, nz = plane.z, w = plane.w;

            // Test positive corner (farthest point along plane normal)
            final float px = nx < 0.0f ? minX : maxX;
            final float py = ny < 0.0f ? minY : maxY;
            final float pz = nz < 0.0f ? minZ : maxZ;

            // If positive corner is outside, box is completely outside frustum
            if (nx * px + ny * py + nz * pz + w < 0.0f) {
                return i; // Return failing plane index (0-5)
            }

            // Test negative corner (nearest point along plane normal)
            final float nx2 = nx < 0.0f ? maxX : minX;
            final float ny2 = ny < 0.0f ? maxY : minY;
            final float nz2 = nz < 0.0f ? maxZ : minZ;

            // If negative corner is outside, box intersects this plane
            if (nx * nx2 + ny * ny2 + nz * nz2 + w < 0.0f) {
                allInside = false;
            }
        }

        return allInside ? INSIDE : INTERSECT;
    }
}