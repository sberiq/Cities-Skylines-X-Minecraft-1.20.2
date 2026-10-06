package dev.citiescraft.bridge;

/** Affine mapping from Minecraft block coordinates to Cities world coordinates. */
final class WorldTransform {
    private final double scale;
    private final double yawRadians;
    private final double cosine;
    private final double sine;
    private final double minecraftOriginX;
    private final double minecraftOriginY;
    private final double minecraftOriginZ;
    private final double citiesOriginX;
    private final double citiesOriginY;
    private final double citiesOriginZ;

    WorldTransform(double scale, double yawDegrees,
                   double minecraftOriginX, double minecraftOriginY, double minecraftOriginZ,
                   double citiesOriginX, double citiesOriginY, double citiesOriginZ) {
        this.scale = scale;
        this.yawRadians = Math.toRadians(yawDegrees);
        this.cosine = Math.cos(yawRadians);
        this.sine = Math.sin(yawRadians);
        this.minecraftOriginX = minecraftOriginX;
        this.minecraftOriginY = minecraftOriginY;
        this.minecraftOriginZ = minecraftOriginZ;
        this.citiesOriginX = citiesOriginX;
        this.citiesOriginY = citiesOriginY;
        this.citiesOriginZ = citiesOriginZ;
    }

    Point toCities(double x, double y, double z) {
        double dx = (x - minecraftOriginX) * scale;
        double dy = (y - minecraftOriginY) * scale;
        double dz = (z - minecraftOriginZ) * scale;
        return new Point(citiesOriginX + cosine * dx + sine * dz,
                citiesOriginY + dy,
                citiesOriginZ - sine * dx + cosine * dz);
    }

    Point toMinecraft(double x, double y, double z) {
        double dx = x - citiesOriginX;
        double dy = y - citiesOriginY;
        double dz = z - citiesOriginZ;
        return new Point(minecraftOriginX + (cosine * dx - sine * dz) / scale,
                minecraftOriginY + dy / scale,
                minecraftOriginZ + (sine * dx + cosine * dz) / scale);
    }

    // Minecraft yaw increases toward -X; Unity Euler yaw increases toward +X.
    double yawToCities(double minecraftYaw) { return wrapDegrees(Math.toDegrees(yawRadians) - minecraftYaw); }
    double yawToMinecraft(double citiesYaw) { return wrapDegrees(Math.toDegrees(yawRadians) - citiesYaw); }

    Bounds toMinecraftBounds(double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ) {
        double outMinX = Double.POSITIVE_INFINITY;
        double outMinY = Double.POSITIVE_INFINITY;
        double outMinZ = Double.POSITIVE_INFINITY;
        double outMaxX = Double.NEGATIVE_INFINITY;
        double outMaxY = Double.NEGATIVE_INFINITY;
        double outMaxZ = Double.NEGATIVE_INFINITY;
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    Point point = toMinecraft(x == 0 ? minX : maxX,
                            y == 0 ? minY : maxY, z == 0 ? minZ : maxZ);
                    outMinX = Math.min(outMinX, point.x);
                    outMinY = Math.min(outMinY, point.y);
                    outMinZ = Math.min(outMinZ, point.z);
                    outMaxX = Math.max(outMaxX, point.x);
                    outMaxY = Math.max(outMaxY, point.y);
                    outMaxZ = Math.max(outMaxZ, point.z);
                }
            }
        }
        return new Bounds(outMinX, outMinY, outMinZ, outMaxX, outMaxY, outMaxZ);
    }

    static double wrapDegrees(double value) {
        value %= 360.0;
        if (value >= 180.0) value -= 360.0;
        if (value < -180.0) value += 360.0;
        return value;
    }

    static final class Point {
        final double x, y, z;
        Point(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    static final class Bounds {
        final double minX, minY, minZ, maxX, maxY, maxZ;
        Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }
}
