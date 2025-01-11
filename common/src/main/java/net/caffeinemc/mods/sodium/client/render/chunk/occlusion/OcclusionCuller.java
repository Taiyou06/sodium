package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.collections.DoubleBufferedQueue;
import net.caffeinemc.mods.sodium.client.util.collections.ReadQueue;
import net.caffeinemc.mods.sodium.client.util.collections.WriteQueue;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class OcclusionCuller {
    private final Long2ReferenceMap<RenderSection> sections;
    private final Level level;
    private final DoubleBufferedQueue<RenderSection> queue = new DoubleBufferedQueue<>();

    private int lastProcessedFrame = -1;
    private final List<RenderSection> lastVisibleSections = new ArrayList<>();

    private static final float CHUNK_SECTION_RADIUS = 8.0f;
    private static final float CHUNK_SECTION_SIZE = CHUNK_SECTION_RADIUS + 1.0f + 0.125f;

    private static class LayerTraversalState {
        private final float[] quadrantImportance = new float[4];
        private final boolean[] quadrantActive = new boolean[4];

        public LayerTraversalState() {
            Arrays.fill(quadrantActive, true);
        }

        public void deactivateQuadrant(int quadrant) {
            if (quadrantActive[quadrant]) {
                quadrantActive[quadrant] = false;
            }
        }

        public boolean isQuadrantActive(int quadrant) {
            return quadrantActive[quadrant];
        }

        public float getQuadrantImportance(int quadrant) {
            return quadrantImportance[quadrant];
        }
    }

    public OcclusionCuller(Long2ReferenceMap<RenderSection> sections, Level level) {
        this.sections = sections;
        this.level = level;
    }

    public void findVisible(Visitor visitor, Viewport viewport, float searchDistance,
                            boolean useOcclusionCulling, int frame) {
        if (viewport == null) {
            throw new IllegalArgumentException("Viewport cannot be null");
        }

        if (!IrisCheck.checkIrisShouldDisable()) {
            processVisibility(visitor, viewport, searchDistance, useOcclusionCulling, frame);
            return;
        }

        int frameInterval = SodiumClientMod.options().performance.chunkFrameInterval;
        boolean shouldRecalculate = frame - lastProcessedFrame >= frameInterval;

        if (shouldRecalculate) {
            lastVisibleSections.clear();
            Visitor cachingVisitor = section -> {
                visitor.visit(section);
                lastVisibleSections.add(section);
            };
            this.lastProcessedFrame = frame;
            processVisibility(cachingVisitor, viewport, searchDistance, useOcclusionCulling, frame);
        } else {
            for (RenderSection section : lastVisibleSections) {
                if (!section.isDisposed()) {
                    section.setLastVisibleFrame(frame);
                    visitor.visit(section);
                }
            }
        }
    }

    private void processVisibility(Visitor visitor, Viewport viewport, float searchDistance,
                                   boolean useOcclusionCulling, int frame) {
        final var queues = this.queue;
        queues.reset();

        this.init(visitor, queues.write(), viewport, searchDistance, useOcclusionCulling, frame);

        while (queues.flip()) {
            processQueue(visitor, viewport, searchDistance, useOcclusionCulling, frame,
                    queues.read(), queues.write());
        }

        this.addNearbySections(visitor, viewport, searchDistance, frame);
    }

    private void addNearbySections(Visitor visitor, Viewport viewport, float searchDistance, int frame) {
        var origin = viewport.getChunkCoord();
        var transform = viewport.getTransform();

        // Calculate normalized view direction vector
        double yaw = Math.toRadians(transform.intY);
        double pitch = Math.toRadians(transform.intX);
        float viewX = (float)(Math.cos(yaw) * Math.cos(pitch));
        float viewY = (float)(Math.sin(pitch));
        float viewZ = (float)(Math.sin(yaw) * Math.cos(pitch));

        // Use ArrayList for initial gathering
        var preFilteredSections = new ArrayList<SectionCandidate>(27);
        int sectionsFound = 0;
        float maxDistanceSq = searchDistance * searchDistance;

        int searchRadius = 1;
        for (var dx = -searchRadius; dx <= searchRadius; dx++) {
            for (var dy = -searchRadius; dy <= searchRadius; dy++) {
                for (var dz = -searchRadius; dz <= searchRadius; dz++) {
                    // Skip center section
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    // Quick distance check using manhattan distance
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 3) {
                        continue;
                    }

                    var section = this.getRenderSection(
                            origin.getX() + dx,
                            origin.getY() + dy,
                            origin.getZ() + dz
                    );

                    if (section == null || section.getLastVisibleFrame() == frame) {
                        continue;
                    }

                    // Calculate direction and distance to section center
                    float dirX = (float) (section.getCenterX() - transform.x);
                    float dirY = (float) (section.getCenterY() - transform.y);
                    float dirZ = (float) (section.getCenterZ() - transform.z);

                    float distanceSq = dirX * dirX + dirY * dirY + dirZ * dirZ;

                    // Early distance culling
                    if (distanceSq > maxDistanceSq) {
                        continue;
                    }

                    float distance = (float) Math.sqrt(distanceSq);

                    // Normalize direction
                    if (distance > 0) {
                        dirX /= distance;
                        dirY /= distance;
                        dirZ /= distance;
                    }

                    // Calculate alignment with view direction
                    float alignment = dirX * viewX + dirY * viewY + dirZ * viewZ;

                    // More aggressive culling for distant sections
                    float cullAngle = -0.5f + (distance / searchDistance) * 0.3f;
                    if (alignment < cullAngle) {
                        continue;
                    }

                    // Calculate priority based on alignment and distance
                    float alignmentFactor = (alignment + 1.0f) * 0.5f;
                    float distanceFactor = 1.0f - (distance / searchDistance);
                    float priority = alignmentFactor * distanceFactor;

                    preFilteredSections.add(new SectionCandidate(section, priority, distance));
                    sectionsFound++;
                }
            }
        }

        // Process found sections
        if (sectionsFound > 0) {
            preFilteredSections.sort(null);
            int maxToProcess = Math.min(8, sectionsFound);

            for (int i = 0; i < maxToProcess; i++) {
                var candidate = preFilteredSections.get(i);
                var section = candidate.section;

                // Final visibility check
                if (isWithinFrustum(viewport, section)) {
                    section.setLastVisibleFrame(frame);
                    visitor.visit(section);
                }
            }
        }
    }

    private record SectionCandidate(RenderSection section, float priority, float distance)
            implements Comparable<SectionCandidate> {
        @Override
        public int compareTo(SectionCandidate other) {
            // First compare by priority bands
            int thisBand = getPriorityBand(this.priority);
            int otherBand = getPriorityBand(other.priority);

            if (thisBand != otherBand) {
                return otherBand - thisBand;
            }

            // Within same band, closer sections are higher priority
            return Float.compare(this.distance, other.distance);
        }

        private static int getPriorityBand(float priority) {
            if (priority > 0.7f) return 2; // High priority
            if (priority > 0.3f) return 1; // Medium priority
            return 0; // Low priority
        }
    }


    private static void processQueue(Visitor visitor, Viewport viewport, float searchDistance,
                                     boolean useOcclusionCulling, int frame,
                                     ReadQueue<RenderSection> readQueue,
                                     WriteQueue<RenderSection> writeQueue) {
        RenderSection section;

        while ((section = readQueue.dequeue()) != null) {
            if (!isSectionVisible(section, viewport, searchDistance)) {
                continue;
            }

            visitor.visit(section);

            int connections;

            if (useOcclusionCulling) {
                var sectionVisibilityData = section.getVisibilityData();
                sectionVisibilityData &= getAngleVisibilityMask(viewport, section);
                connections = VisibilityEncoding.getConnections(sectionVisibilityData,
                        section.getIncomingDirections());
            } else {
                connections = GraphDirectionSet.ALL;
            }

            connections &= getOutwardDirections(viewport.getChunkCoord(), section);
            visitNeighbors(writeQueue, section, connections, frame);
        }
    }

    private void init(Visitor visitor, WriteQueue<RenderSection> queue, Viewport viewport,
                      float searchDistance, boolean useOcclusionCulling, int frame) {
        var origin = viewport.getChunkCoord();

        if (origin.getY() < this.level.getMinSection()) {
            this.initOutsideWorldHeight(queue, viewport, searchDistance, frame,
                    this.level.getMinSection(), GraphDirection.DOWN);
        } else if (origin.getY() >= this.level.getMaxSection()) {
            this.initOutsideWorldHeight(queue, viewport, searchDistance, frame,
                    this.level.getMaxSection() - 1, GraphDirection.UP);
        } else {
            this.initWithinWorld(visitor, queue, viewport, useOcclusionCulling, frame);
        }
    }

    private void initOutsideWorldHeight(WriteQueue<RenderSection> queue, Viewport viewport,
                                        float searchDistance, int frame, int height, int direction) {
        var origin = viewport.getChunkCoord();
        var radius = Mth.floor(searchDistance / 16.0f);
        var transform = viewport.getTransform();

        float yaw = (float) Math.toRadians(transform.intY);
        float pitch = (float) Math.toRadians(transform.intX);
        float viewX = (float) (Math.cos(yaw) * Math.cos(pitch));
        float viewZ = (float) (Math.sin(yaw) * Math.cos(pitch));

        // Process origin section
        tryVisitNode(queue, origin.getX(), height, origin.getZ(), direction, frame, viewport);

        // Process layers using adaptive quadrants
        for (int layer = 1; layer <= radius; layer++) {
            float layerDistanceSq = layer * layer * 256.0f;
            if (layerDistanceSq > searchDistance * searchDistance) {
                break;
            }

            // Create quadrant hierarchy for this layer
            AdaptiveQuadrant rootQuadrant = new AdaptiveQuadrant(-layer, layer, -layer, layer, 0);
            subdivideQuadrantsAdaptively(rootQuadrant, viewX, viewZ, searchDistance, 3);

            // Process all quadrants in the hierarchy
            processQuadrantHierarchy(rootQuadrant, queue, viewport, origin, height, direction, frame,
                    viewX, viewZ, searchDistance);
        }
    }

    private void subdivideQuadrantsAdaptively(AdaptiveQuadrant quadrant, float viewX, float viewZ,
                                              float maxDistance, int maxLevel) {
        if (quadrant.level >= maxLevel) return;

        float importance = quadrant.getImportance(viewX, viewZ, maxDistance);
        float subdivisionThreshold = 0.5f / (quadrant.level + 1);

        if (importance > subdivisionThreshold) {
            quadrant.subdivide();
            for (AdaptiveQuadrant sub : quadrant.getSubQuadrants()) {
                subdivideQuadrantsAdaptively(sub, viewX, viewZ, maxDistance, maxLevel);
            }
        }
    }

    private void processQuadrantHierarchy(AdaptiveQuadrant quadrant,
                                          WriteQueue<RenderSection> queue,
                                          Viewport viewport,
                                          SectionPos origin,
                                          int height,
                                          int direction,
                                          int frame,
                                          float viewX,
                                          float viewZ,
                                          float searchDistance) {
        // Process subdivided quadrants recursively
        if (quadrant.isSubdivided()) {
            for (AdaptiveQuadrant sub : quadrant.getSubQuadrants()) {
                processQuadrantHierarchy(sub, queue, viewport, origin, height, direction, frame,
                        viewX, viewZ, searchDistance);
            }
            return;
        }

        // Calculate appropriate stride for this quadrant
        float importance = quadrant.getImportance(viewX, viewZ, searchDistance);
        int stride = calculateAdaptiveStride(importance, quadrant.level);

        // Skip processing if quadrant importance is too low
        if (importance < 0.05f) return;

        // Quick frustum check for the entire quadrant
        if (!isQuadrantPotentiallyVisible(viewport, origin, height, quadrant.minX, quadrant.maxX,
                quadrant.minZ, quadrant.maxZ)) {
            return;
        }

        // Process all sections in the quadrant with calculated stride
        for (int x = quadrant.minX; x < quadrant.maxX; x += stride) {
            for (int z = quadrant.minZ; z < quadrant.maxZ; z += stride) {
                RenderSection section = getRenderSection(
                        origin.getX() + x,
                        height,
                        origin.getZ() + z
                );

                if (section != null && isWithinFrustum(viewport, section)) {
                    visitNode(queue, section, GraphDirectionSet.of(direction), frame);
                }
            }
        }
    }

    private int calculateAdaptiveStride(float importance, int level) {
        int baseStride = 1 << level;

        if (importance < 0.3f) return baseStride * 4;
        if (importance < 0.6f) return baseStride * 2;
        return baseStride;
    }

    private void processQuadrantsInImportanceOrder(WriteQueue<RenderSection> queue,
                                                   Viewport viewport,
                                                   SectionPos origin,
                                                   int height,
                                                   int layer,
                                                   int direction,
                                                   int frame,
                                                   LayerTraversalState state,
                                                   int baseStride) {
        Integer[] quadrants = {0, 1, 2, 3};
        Arrays.sort(quadrants, (a, b) -> Float.compare(
                state.getQuadrantImportance(b),
                state.getQuadrantImportance(a)
        ));

        boolean foundVisible = false;

        for (int quadrant : quadrants) {
            if (!state.isQuadrantActive(quadrant)) {
                continue;
            }

            float importance = state.getQuadrantImportance(quadrant);
            int adaptiveStride = calculateAdaptiveStride(baseStride, importance);

            if (processQuadrantWithImportance(queue, viewport, origin, height, layer,
                    quadrant, direction, frame, adaptiveStride, importance)) {
                foundVisible = true;
            } else {
                state.deactivateQuadrant(quadrant);
            }
        }

        if (!foundVisible) {
            for (int q = 0; q < 4; q++) {
                state.deactivateQuadrant(q);
            }
        }
    }

    private static int calculateAdaptiveStride(int baseStride, float quadrantImportance) {
        if (quadrantImportance < 0.3f) return baseStride * 2;
        if (quadrantImportance < 0.6f) return baseStride + 1;
        return baseStride;
    }

    private boolean processQuadrantWithImportance(WriteQueue<RenderSection> queue,
                                                  Viewport viewport,
                                                  SectionPos origin,
                                                  int height,
                                                  int layer,
                                                  int quadrant,
                                                  int direction,
                                                  int frame,
                                                  int stride,
                                                  float importance) {
        int[] dirs = getQuadrantDirections(quadrant);
        int xDir = dirs[0];
        int zDir = dirs[1];

        int startX = xDir * layer;
        int endX = 0;
        int startZ = 0;
        int endZ = zDir * layer;

        if (!isQuadrantPotentiallyVisible(viewport, origin, height, startX, endX, startZ, endZ) ||
                importance < 0.1f) {
            return false;
        }

        boolean foundVisible = false;
        float cullAngle = -0.3f + (layer * 16.0f / (xDir * 16.0f)) * 0.4f;

        for (int x = startX; x != endX; x -= (xDir * stride)) {
            for (int z = startZ; z != endZ; z += (zDir * stride)) {
                RenderSection section = getRenderSection(
                        origin.getX() + x,
                        height,
                        origin.getZ() + z
                );

                if (section != null &&
                        isSectionImportant(section, viewport, cullAngle) &&
                        isWithinFrustum(viewport, section)) {
                    visitNode(queue, section, GraphDirectionSet.of(direction), frame);
                    foundVisible = true;
                }
            }
        }

        return foundVisible;
    }

    private boolean isSectionImportant(RenderSection section, Viewport viewport, float cullAngle) {
        var transform = viewport.getTransform();

        float dx = (float) (section.getCenterX() - transform.x);
        float dy = (float) (section.getCenterY() - transform.y);
        float dz = (float) (section.getCenterZ() - transform.z);

        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist == 0) return true;

        dx /= dist;
        dy /= dist;
        dz /= dist;

        float yaw = (float) Math.toRadians(transform.intY);
        float pitch = (float) Math.toRadians(transform.intX);
        float viewX = (float) (Math.cos(yaw) * Math.cos(pitch));
        float viewY = (float) (Math.sin(pitch));
        float viewZ = (float) (Math.sin(yaw) * Math.cos(pitch));

        float alignment = dx * viewX + dy * viewY + dz * viewZ;
        return alignment > cullAngle;
    }

    private static void visitNeighbors(final WriteQueue<RenderSection> queue,
                                       RenderSection section, int outgoing, int frame) {
        outgoing &= section.getAdjacentMask();
        if (outgoing == GraphDirectionSet.NONE) return;
        queue.ensureCapacity(6);

        if (GraphDirectionSet.contains(outgoing, GraphDirection.DOWN)) {
            visitNode(queue, section.adjacentDown, GraphDirectionSet.of(GraphDirection.UP), frame);
        }

        if (GraphDirectionSet.contains(outgoing, GraphDirection.UP)) {
            visitNode(queue, section.adjacentUp, GraphDirectionSet.of(GraphDirection.DOWN), frame);
        }

        if (GraphDirectionSet.contains(outgoing, GraphDirection.NORTH)) {
            visitNode(queue, section.adjacentNorth, GraphDirectionSet.of(GraphDirection.SOUTH), frame);
        }

        if (GraphDirectionSet.contains(outgoing, GraphDirection.SOUTH)) {
            visitNode(queue, section.adjacentSouth, GraphDirectionSet.of(GraphDirection.NORTH), frame);
        }

        if (GraphDirectionSet.contains(outgoing, GraphDirection.WEST)) {
            visitNode(queue, section.adjacentWest, GraphDirectionSet.of(GraphDirection.EAST), frame);
        }

        if (GraphDirectionSet.contains(outgoing, GraphDirection.EAST)) {
            visitNode(queue, section.adjacentEast, GraphDirectionSet.of(GraphDirection.WEST), frame);
        }
    }

    private static void visitNode(final WriteQueue<RenderSection> queue,
                                  @NotNull RenderSection render, int incoming, int frame) {
        int frameDiff = frame - render.getLastVisibleFrame();
        boolean isNewFrame = frameDiff != 0;
        render.incomingDirections = (isNewFrame ? 0 : render.incomingDirections) | incoming;
        render.lastVisibleFrame = frame;
        if (isNewFrame) {
            queue.enqueue(render);
        }
    }

    private void initWithinWorld(Visitor visitor, WriteQueue<RenderSection> queue,
                                 Viewport viewport, boolean useOcclusionCulling, int frame) {
        var origin = viewport.getChunkCoord();
        var section = this.getRenderSection(origin.getX(), origin.getY(), origin.getZ());

        if (section == null) {
            return;
        }

        section.setLastVisibleFrame(frame);
        section.setIncomingDirections(GraphDirectionSet.NONE);

        visitor.visit(section);

        int outgoing = useOcclusionCulling ?
                VisibilityEncoding.getConnections(section.getVisibilityData()) :
                GraphDirectionSet.ALL;

        visitNeighbors(queue, section, outgoing, frame);
    }

    private boolean isQuadrantPotentiallyVisible(Viewport viewport,
                                                 SectionPos origin,
                                                 int height,
                                                 int startX,
                                                 int endX,
                                                 int startZ,
                                                 int endZ) {
        int minX = Math.min(startX, endX) + origin.getX();
        int maxX = Math.max(startX, endX) + origin.getX();
        int minZ = Math.min(startZ, endZ) + origin.getZ();
        int maxZ = Math.max(startZ, endZ) + origin.getZ();

        float size = CHUNK_SECTION_SIZE * 1.5f;
        float centerX = (minX + maxX) * 8.0f;
        float centerZ = (minZ + maxZ) * 8.0f;
        float sizeX = (maxX - minX + 1) * 8.0f + size;
        float sizeZ = (maxZ - minZ + 1) * 8.0f + size;

        return viewport.isBoxVisible((int) centerX, (int) (height * 16.0f), (int) centerZ,
                sizeX, size, sizeZ);
    }

    private static final long UP_DOWN_OCCLUDED = (1L << VisibilityEncoding.bit(GraphDirection.DOWN, GraphDirection.UP)) |
            (1L << VisibilityEncoding.bit(GraphDirection.UP, GraphDirection.DOWN));
    private static final long NORTH_SOUTH_OCCLUDED = (1L << VisibilityEncoding.bit(GraphDirection.NORTH, GraphDirection.SOUTH)) |
            (1L << VisibilityEncoding.bit(GraphDirection.SOUTH, GraphDirection.NORTH));
    private static final long WEST_EAST_OCCLUDED = (1L << VisibilityEncoding.bit(GraphDirection.WEST, GraphDirection.EAST)) |
            (1L << VisibilityEncoding.bit(GraphDirection.EAST, GraphDirection.WEST));

    private static long getAngleVisibilityMask(Viewport viewport, RenderSection section) {
        var transform = viewport.getTransform();
        var dx = Math.abs(transform.x - section.getCenterX());
        var dy = Math.abs(transform.y - section.getCenterY());
        var dz = Math.abs(transform.z - section.getCenterZ());

        var angleOcclusionMask = 0L;
        if (dx > dy || dz > dy) {
            angleOcclusionMask |= UP_DOWN_OCCLUDED;
        }
        if (dx > dz || dy > dz) {
            angleOcclusionMask |= NORTH_SOUTH_OCCLUDED;
        }
        if (dy > dx || dz > dx) {
            angleOcclusionMask |= WEST_EAST_OCCLUDED;
        }

        return ~angleOcclusionMask;
    }

    private static boolean isSectionVisible(RenderSection section, Viewport viewport, float maxDistance) {
        return isWithinRenderDistance(viewport.getTransform(), section, maxDistance) &&
                isWithinFrustum(viewport, section);
    }

    private static boolean isWithinRenderDistance(CameraTransform camera, RenderSection section, float maxDistance) {
        int ox = section.getOriginX() - camera.intX;
        int oy = section.getOriginY() - camera.intY;
        int oz = section.getOriginZ() - camera.intZ;

        float dx = nearestToZero(ox, ox + 16) - camera.fracX;
        float dy = nearestToZero(oy, oy + 16) - camera.fracY;
        float dz = nearestToZero(oz, oz + 16) - camera.fracZ;

        return (((dx * dx) + (dz * dz)) < (maxDistance * maxDistance)) &&
                (Math.abs(dy) < maxDistance);
    }

    private static int nearestToZero(int min, int max) {
        int clamped = 0;
        if (min > 0) { clamped = min; }
        if (max < 0) { clamped = max; }
        return clamped;
    }

    private int[] getQuadrantDirections(int quadrant) {
        switch (quadrant) {
            case 0: return new int[]{ 1, -1 }; // NE
            case 1: return new int[]{ 1,  1 }; // SE
            case 2: return new int[]{ -1, 1 }; // SW
            case 3: return new int[]{ -1,-1 }; // NW
            default: throw new IllegalArgumentException("Invalid quadrant: " + quadrant);
        }
    }

    private void tryVisitNode(WriteQueue<RenderSection> queue, int x, int y, int z,
                              int direction, int frame, Viewport viewport) {
        RenderSection section = this.getRenderSection(x, y, z);

        if (section == null || !isWithinFrustum(viewport, section)) {
            return;
        }

        visitNode(queue, section, GraphDirectionSet.of(direction), frame);
    }

    private RenderSection getRenderSection(int x, int y, int z) {
        return this.sections.get(SectionPos.asLong(x, y, z));
    }

    public static boolean isWithinFrustum(Viewport viewport, RenderSection section) {
        return viewport.isBoxVisible(section.getCenterX(), section.getCenterY(), section.getCenterZ(),
                CHUNK_SECTION_SIZE, CHUNK_SECTION_SIZE, CHUNK_SECTION_SIZE);
    }

    private static int getOutwardDirections(SectionPos origin, RenderSection section) {
        int planes = 0;

        planes |= section.getChunkX() <= origin.getX() ? 1 << GraphDirection.WEST  : 0;
        planes |= section.getChunkX() >= origin.getX() ? 1 << GraphDirection.EAST  : 0;

        planes |= section.getChunkY() <= origin.getY() ? 1 << GraphDirection.DOWN  : 0;
        planes |= section.getChunkY() >= origin.getY() ? 1 << GraphDirection.UP    : 0;

        planes |= section.getChunkZ() <= origin.getZ() ? 1 << GraphDirection.NORTH : 0;
        planes |= section.getChunkZ() >= origin.getZ() ? 1 << GraphDirection.SOUTH : 0;

        return planes;
    }

    public interface Visitor {
        void visit(RenderSection section);
    }
}