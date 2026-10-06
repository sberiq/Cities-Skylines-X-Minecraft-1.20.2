package dev.citiescraft.minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/** Immutable, client-supplied Cities collision snapshot indexed by Minecraft chunks. */
public final class CityCollisionWorld {
    private static final int CHUNK_SIZE = 16;
    private static volatile Snapshot current = Snapshot.EMPTY;

    private CityCollisionWorld() { }

    public static long sequence() { return current.sequence; }
    public static int shapeCount() { return current.shapeCount; }

    public static void clear() { current = Snapshot.EMPTY; }

    static void accept(String[] fields) {
        if (fields.length < 2 || !"CITYWORLD".equals(fields[0])) return;
        try {
            long sequence = Long.parseLong(fields[1]);
            if (sequence < 0 || sequence <= current.sequence) return;
            if (fields.length > 4483) return;

            Map<Long, List<VoxelShape>> bins = new HashMap<>();
            int shapeCount = 0;
            for (int index = 2; index < fields.length; index++) {
                String[] values = fields[index].split(",", -1);
                if (values.length != 7 || !("T".equals(values[0]) || "B".equals(values[0]))) return;
                double minX = finite(values[1]);
                double minY = finite(values[2]);
                double minZ = finite(values[3]);
                double maxX = finite(values[4]);
                double maxY = finite(values[5]);
                double maxZ = finite(values[6]);
                if (minX >= maxX || minY >= maxY || minZ >= maxZ) continue;

                VoxelShape shape = VoxelShapes.cuboid(new Box(minX, minY, minZ, maxX, maxY, maxZ));
                Box bounds = shape.getBoundingBox();
                int minChunkX = floorChunk(bounds.minX);
                int maxChunkX = floorChunk(bounds.maxX);
                int minChunkZ = floorChunk(bounds.minZ);
                int maxChunkZ = floorChunk(bounds.maxZ);
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        long key = chunkKey(chunkX, chunkZ);
                        List<VoxelShape> shapes = bins.get(key);
                        if (shapes == null) {
                            shapes = new ArrayList<>();
                            bins.put(key, shapes);
                        }
                        shapes.add(shape);
                    }
                }
                shapeCount++;
            }
            current = new Snapshot(sequence, shapeCount, bins);
        } catch (RuntimeException exception) {
            // A malformed or partial snapshot never replaces the last complete city collision view.
        }
    }

    public static List<VoxelShape> collisions(Box sweptBounds) {
        Snapshot snapshot = current;
        if (snapshot.shapeCount == 0) return Collections.emptyList();

        int minChunkX = floorChunk(sweptBounds.minX);
        int maxChunkX = floorChunk(sweptBounds.maxX);
        int minChunkZ = floorChunk(sweptBounds.minZ);
        int maxChunkZ = floorChunk(sweptBounds.maxZ);
        ArrayList<VoxelShape> result = new ArrayList<>();
        IdentityHashMap<VoxelShape, Boolean> seen = new IdentityHashMap<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                List<VoxelShape> bucket = snapshot.bins.get(chunkKey(chunkX, chunkZ));
                if (bucket == null) continue;
                for (VoxelShape shape : bucket) {
                    if (seen.put(shape, Boolean.TRUE) == null && shape.getBoundingBox().intersects(sweptBounds)) {
                        result.add(shape);
                    }
                }
            }
        }
        return result;
    }

    private static int floorChunk(double coordinate) {
        return (int) Math.floor(coordinate / CHUNK_SIZE);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static double finite(String text) {
        double value = Double.parseDouble(text);
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new NumberFormatException("non-finite box coordinate");
        return value;
    }

    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(-1, 0, Collections.<Long, List<VoxelShape>>emptyMap());
        final long sequence;
        final int shapeCount;
        final Map<Long, List<VoxelShape>> bins;

        Snapshot(long sequence, int shapeCount, Map<Long, List<VoxelShape>> bins) {
            this.sequence = sequence;
            this.shapeCount = shapeCount;
            this.bins = bins;
        }
    }
}
