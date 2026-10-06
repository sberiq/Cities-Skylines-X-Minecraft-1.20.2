package dev.citiescraft.minecraft;

import java.util.List;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

/** Raycasts the currently streamed city collision proxies, without changing Minecraft's world. */
public final class CitySurfaceRaycaster {
    private static final double QUERY_EPSILON = 1.0E-4;
    private static final double CELL_NUDGE = 1.0E-4;

    private CitySurfaceRaycaster() { }

    /**
     * Returns the nearest synthetic block hit on a streamed terrain/building proxy, or
     * {@code null} when the ray misses. Coordinates and shape boxes are in Minecraft
     * world space. The returned block position is the voxel containing the entry point.
     */
    public static BlockHitResult raycast(Vec3d start, Vec3d end) {
        if (start == null || end == null || start.squaredDistanceTo(end) < 1.0E-8) return null;

        Box rayBounds = new Box(start, end).expand(QUERY_EPSILON);
        List<VoxelShape> candidates = CityCollisionWorld.collisions(rayBounds);
        BlockHitResult closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;

        for (VoxelShape shape : candidates) {
            BlockHitResult hit = shape.raycast(start, end, BlockPos.ORIGIN);
            if (hit == null) continue;
            double distance = start.squaredDistanceTo(hit.getPos());
            if (distance >= closestDistance) continue;

            // A BlockHitResult's block position is the hit block. Vanilla derives a
            // placement destination by offsetting this position along the hit face.
            BlockPos hitCell = BlockPos.ofFloored(
                    hit.getPos().x - hit.getSide().getOffsetX() * CELL_NUDGE,
                    hit.getPos().y - hit.getSide().getOffsetY() * CELL_NUDGE,
                    hit.getPos().z - hit.getSide().getOffsetZ() * CELL_NUDGE);
            closest = new BlockHitResult(hit.getPos(), hit.getSide(), hitCell, false);
            closestDistance = distance;
        }
        return closest;
    }
}
