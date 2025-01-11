package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

public class AdaptiveQuadrant {
    public final int minX;
    public final int maxX;
    public final int minZ;
    public final int maxZ;
    public final int level;
    private final float centerX, centerZ;
    private AdaptiveQuadrant[] subQuadrants;

    public AdaptiveQuadrant(int minX, int maxX, int minZ, int maxZ, int level) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.level = level;
        this.centerX = (minX + maxX) / 2.0f;
        this.centerZ = (minZ + maxZ) / 2.0f;
    }

    public void subdivide() {
        if (subQuadrants != null) return;

        int midX = (minX + maxX) / 2;
        int midZ = (minZ + maxZ) / 2;

        subQuadrants = new AdaptiveQuadrant[4];
        subQuadrants[0] = new AdaptiveQuadrant(minX, midX, minZ, midZ, level + 1);   // NW
        subQuadrants[1] = new AdaptiveQuadrant(midX, maxX, minZ, midZ, level + 1);   // NE
        subQuadrants[2] = new AdaptiveQuadrant(minX, midX, midZ, maxZ, level + 1);   // SW
        subQuadrants[3] = new AdaptiveQuadrant(midX, maxX, midZ, maxZ, level + 1);   // SE
    }

    public boolean isSubdivided() {
        return subQuadrants != null;
    }

    public float getImportance(float viewX, float viewZ, float maxDistance) {
        float dx = centerX - viewX;
        float dz = centerZ - viewZ;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);

        if (distance == 0) return 1.0f;

        dx /= distance;
        dz /= distance;

        float alignment = -(dx * viewX + dz * viewZ);
        float distanceFactor = 1.0f - Math.min(1.0f, distance / maxDistance);

        return Math.max(0.0f, alignment) * distanceFactor;
    }

    public AdaptiveQuadrant[] getSubQuadrants() {
        return subQuadrants;
    }

    public boolean containsPoint(float x, float z) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }
}