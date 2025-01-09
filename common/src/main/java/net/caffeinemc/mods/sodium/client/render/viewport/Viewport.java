package net.caffeinemc.mods.sodium.client.render.viewport;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.joml.Vector3d;

public final class Viewport {
    private final Frustum frustum;
    private final CameraTransform transform;

    private final SectionPos sectionCoords;
    private final BlockPos blockCoords;

    public Viewport(Frustum frustum, Vector3d position) {
        this.frustum = frustum;
        this.transform = new CameraTransform(position.x, position.y, position.z);

        this.sectionCoords = SectionPos.of(
                SectionPos.posToSectionCoord(position.x),
                SectionPos.posToSectionCoord(position.y),
                SectionPos.posToSectionCoord(position.z)
        );

        this.blockCoords = BlockPos.containing(position.x, position.y, position.z);
    }

    /**
     * SIMD-friendly implementation for future JVM auto-vectorization
     * Note: This would require JVM support for vectorization
     */
    public boolean isBoxVisible(int intOriginX, int intOriginY, int intOriginZ, float floatSizeX, float floatSizeY, float floatSizeZ) {
        // Group operations to help auto-vectorization
        float[] origins = new float[3];
        origins[0] = intOriginX - (this.transform.intX + this.transform.fracX);
        origins[1] = intOriginY - (this.transform.intY + this.transform.fracY);
        origins[2] = intOriginZ - (this.transform.intZ + this.transform.fracZ);

        float[] sizes = new float[]{floatSizeX, floatSizeY, floatSizeZ};
        float[] mins = new float[3];
        float[] maxs = new float[3];

        // Vectorizable loop
        for (int i = 0; i < 3; i++) {
            mins[i] = origins[i] - sizes[i];
            maxs[i] = origins[i] + sizes[i];
        }

        return this.frustum.testAab(mins[0], mins[1], mins[2], maxs[0], maxs[1], maxs[2]);
    }

    public CameraTransform getTransform() {
        return this.transform;
    }

    public SectionPos getChunkCoord() {
        return this.sectionCoords;
    }

    public BlockPos getBlockCoord() {
        return this.blockCoords;
    }
}
